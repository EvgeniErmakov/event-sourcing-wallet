import { ChangeDetectionStrategy, Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { ApiFailure, decodeWallet, describeError, WalletApiService } from './api/wallet-api.service';
import {
    CommandBody, CommandKind, LastOperation, MoneyIntent, ProjectionHandlerStatus, RecentWallet, SavedCommand,
    WalletComparison, WalletEvent, WalletState,
} from './api/wallet.models';
import { EventHistoryComponent } from './components/event-history.component';
import { HistoricalStateComponent } from './components/historical-state.component';
import { LastOperationComponent } from './components/last-operation.component';
import { ModelComparisonComponent } from './components/model-comparison.component';
import { WalletOperationsComponent } from './components/wallet-operations.component';
import { WalletSelectorComponent } from './components/wallet-selector.component';
import { WalletStateComponent } from './components/wallet-state.component';
import { MAX_SAFE_VALUE, UUID_PATTERN } from './shared/numbers';

const RECENT_KEY = 'wallet-ui.recent.v1';

/** Координирует локальный экран; состояние и события всегда получает из существующего API. */
@Component({
    selector: 'app-root',
    standalone: true,
    imports: [WalletSelectorComponent, WalletStateComponent, WalletOperationsComponent, EventHistoryComponent,
        HistoricalStateComponent, LastOperationComponent, ModelComparisonComponent],
    templateUrl: './app.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent implements OnDestroy, OnInit {
    private readonly api = inject(WalletApiService);
    private selectionGeneration = 0;
    private stateGeneration = 0;
    private historyGeneration = 0;
    private historyCursor = 0;
    private readPollInFlight = false;
    private readonly readPollTimer = window.setInterval(() => void this.pollReadSide(), 3000);

    readonly selected = signal<string | null>(null);
    readonly comparisonContext = signal(0);
    readonly wallet = signal<WalletState | null>(null);
    readonly stateLoading = signal(false);
    readonly stateError = signal('');
    readonly events = signal<readonly WalletEvent[]>([]);
    readonly historyLoading = signal(false);
    readonly historyLoaded = signal(false);
    readonly historyHasMore = signal(false);
    readonly historyError = signal('');
    readonly busy = signal(false);
    readonly operationError = signal('');
    readonly operationMessage = signal('');
    readonly needsRefresh = signal(false);
    readonly lastOperation = signal<LastOperation | null>(null);
    readonly storageMessage = signal('');
    readonly recent = signal<readonly RecentWallet[]>(this.readRecent());
    readonly handler = signal<ProjectionHandlerStatus | null>(null);
    readonly handlerError = signal('');
    readonly commandWriteVersion = signal(0);
    readonly comparisonLagging = signal(false);
    readonly uncertain = computed(() => this.lastOperation()?.uncertain ?? false);
    readonly createDisabled = computed(() => this.busy() || this.uncertain());
    readonly blockedReason = computed(() => {
        if (this.busy()) return 'Дождитесь ответа на отправленную команду.';
        if (this.uncertain()) return 'Результат последней команды неизвестен. Повторите сохранённый запрос с прежним ключом.';
        if (this.stateLoading()) return 'Загружается актуальное состояние.';
        if (this.stateError()) return 'Текущее состояние read model недоступно. Обновите чтение.';
        const wallet = this.wallet();
        if (wallet && this.commandWriteVersion() > wallet.version) {
            return 'Проекция догоняет последнее событие. Дождитесь обновления read model.';
        }
        if (this.comparisonLagging()) return 'Диагностика показывает отставание проекции. Дождитесь её сходимости.';
        if (this.needsRefresh()) return 'Перед новой командой обновите состояние.';
        if (!wallet) return 'Откройте кошелёк с доступным текущим состоянием.';
        if (wallet.status === 'CLOSED') return 'Кошелёк закрыт. История и просмотр прошлых версий доступны.';
        if (wallet.version >= MAX_SAFE_VALUE) return 'Следующая версия выйдет за безопасный диапазон UI. Команды отключены.';
        return '';
    });
    readonly operationsDisabled = computed(() => this.blockedReason() !== '');

    open(id: string): void {
        const normalized = id.trim().toLowerCase();
        if (!UUID_PATTERN.test(normalized)) return;
        this.select(normalized);
        void this.refresh();
    }

    async create(): Promise<void> {
        if (this.createDisabled()) return;
        try {
            const id = crypto.randomUUID();
            const request = this.command('CreateWallet', id, '', { currency: 'RUB' });
            this.select(id);
            await this.send(request, false);
        } catch (error: unknown) {
            this.operationError.set(describeError(error));
        }
    }

    async performMoney(intent: MoneyIntent): Promise<void> {
        const wallet = this.wallet();
        if (!wallet || this.operationsDisabled()) return;
        // expectedVersion берётся только из последнего успешного GET текущего состояния, никогда из исторической карточки.
        if (!Number.isSafeInteger(intent.amountMinor) || intent.amountMinor <= 0) return;
        if (intent.operation === 'deposits' && BigInt(wallet.balanceMinor) + BigInt(intent.amountMinor) > BigInt(MAX_SAFE_VALUE)) return;
        try {
            await this.send(this.command(intent.operation === 'deposits' ? 'DepositMoney' : 'WithdrawMoney',
                wallet.walletId, `/${intent.operation}`, { amountMinor: intent.amountMinor, expectedVersion: wallet.version }), false);
        } catch (error: unknown) { this.operationError.set(describeError(error)); }
    }

    async close(): Promise<void> {
        const wallet = this.wallet();
        if (!wallet || this.operationsDisabled() || wallet.balanceMinor !== 0) return;
        try {
            await this.send(this.command('CloseWallet', wallet.walletId, '/close', { expectedVersion: wallet.version }), false);
        } catch (error: unknown) { this.operationError.set(describeError(error)); }
    }

    async repeat(): Promise<void> {
        const last = this.lastOperation();
        if (!last || this.busy()) return;
        // Разрешено повторять и для закрытого/невыбранного кошелька: это прежняя команда, а не новая операция.
        await this.send(last.request, true);
    }

    async refresh(): Promise<void> {
        if (!this.selected()) return;
        this.comparisonContext.update(value => value + 1);
        await Promise.all([this.loadState(), this.loadHistory(true), this.loadHandler()]);
    }

    async loadHistory(reset = false): Promise<void> {
        const id = this.selected();
        if (!id || (!reset && (this.historyLoading() || this.busy()))) return;
        const selection = this.selectionGeneration;
        const generation = ++this.historyGeneration;
        if (reset) {
            this.events.set([]);
            this.historyCursor = 0;
            this.historyHasMore.set(false);
            this.historyLoaded.set(false);
        }
        const cursor = this.historyCursor;
        this.historyLoading.set(true);
        this.historyError.set('');
        try {
            const page = await this.api.getEvents(id, cursor);
            if (selection !== this.selectionGeneration || generation !== this.historyGeneration) return;
            this.events.update(items => [...items, ...page.items]);
            this.historyCursor = page.nextAfterVersion;
            this.historyHasMore.set(page.hasMore);
            this.historyLoaded.set(true);
        } catch (error: unknown) {
            if (selection === this.selectionGeneration && generation === this.historyGeneration) {
                this.historyError.set(describeError(error));
            }
        } finally {
            if (selection === this.selectionGeneration && generation === this.historyGeneration) this.historyLoading.set(false);
        }
    }

    private select(id: string): void {
        this.comparisonContext.update(value => value + 1);
        // Одного UUID недостаточно: пользователь может успеть выбрать A → B → A до ответа первого GET.
        ++this.selectionGeneration;
        ++this.stateGeneration;
        ++this.historyGeneration;
        this.selected.set(id);
        this.wallet.set(null);
        this.stateLoading.set(false);
        this.stateError.set('');
        this.events.set([]);
        this.historyCursor = 0;
        this.historyLoading.set(false);
        this.historyLoaded.set(false);
        this.historyHasMore.set(false);
        this.historyError.set('');
        this.operationError.set('');
        this.operationMessage.set('');
        this.needsRefresh.set(false);
        this.commandWriteVersion.set(0);
        this.comparisonLagging.set(false);
    }

    private command(kind: CommandKind, walletId: string, suffix: string, body: CommandBody): SavedCommand {
        // Новый ключ — только для новой команды. Для повторов этот метод вообще не вызывается.
        return Object.freeze({
            kind, walletId, method: kind === 'CreateWallet' ? 'PUT' : 'POST',
            url: `/api/wallets/${walletId}${suffix}`, key: crypto.randomUUID(),
            expectedVersion: 'expectedVersion' in body ? body.expectedVersion : null,
            body: JSON.stringify(body),
        });
    }

    private async send(request: SavedCommand, repeated: boolean): Promise<void> {
        if (this.busy()) return;
        const selection = this.selectionGeneration;
        const wasUncertain = this.uncertain();
        this.busy.set(true);
        this.operationError.set('');
        this.operationMessage.set('');
        this.lastOperation.set({ request, pending: true, uncertain: wasUncertain, repeated,
            status: null, responseBody: null, message: 'Запрос отправляется. Результат ещё не получен.' });
        try {
            const response = await this.api.send(request);
            const message = repeated
                ? 'Сервер успешно ответил на повтор. Ответ команды может относиться к более ранней версии.'
                : 'Команда успешно завершена сервером.';
            this.lastOperation.set({ request, pending: false, uncertain: false, repeated,
                status: response.status, responseBody: response.body ?? '', message });
            try {
                const commandState = decodeWallet(response.body ?? '', request.walletId);
                this.commandWriteVersion.set(Math.max(this.commandWriteVersion(), commandState.version));
            } catch {
                // Сохраняем исходный текст receipt; отдельный GET остаётся источником read model.
            }
            this.remember(request.walletId);
            if (selection === this.selectionGeneration && this.selected() === request.walletId) {
                this.operationMessage.set(message);
            }
            // Receipt не является текущим состоянием. После успеха (включая повтор) обязательно отдельный GET.
            // Если за время команды выбран другой кошелёк, его карточку/историю не трогаем.
            if (this.selected() === request.walletId) {
                await this.refresh();
            } else {
                // Даже для повтора чужого выбранной карточке кошелька читаем актуальное состояние отдельно.
                // Результат не записываем в карточку другого кошелька.
                try { await this.api.getWallet(request.walletId); }
                catch (error: unknown) {
                    this.lastOperation.update(last => last ? { ...last,
                        message: `${message} Актуальное состояние не прочитано: ${describeError(error)}` } : last);
                }
            }
        } catch (error: unknown) {
            const failure = error instanceof ApiFailure ? error : null;
            const uncertain = !failure || failure.status === 0 || failure.status >= 500;
            const message = describeError(error) + (uncertain
                ? ' Результат команды неизвестен: повторите тот же запрос с прежним ключом.' : '');
            this.lastOperation.set({ request, pending: false, uncertain, repeated,
                status: failure?.status ? failure.status : null, responseBody: failure?.rawBody ?? null, message });
            if (selection === this.selectionGeneration && this.selected() === request.walletId) {
                this.operationError.set(message);
                if (failure?.code === 'VERSION_CONFLICT') this.needsRefresh.set(true);
            }
        } finally {
            this.busy.set(false);
        }
    }

    private async loadState(): Promise<void> {
        const id = this.selected();
        if (!id) return;
        const selection = this.selectionGeneration;
        const generation = ++this.stateGeneration;
        this.stateLoading.set(true);
        this.stateError.set('');
        try {
            const state = await this.api.getWallet(id);
            if (selection !== this.selectionGeneration || generation !== this.stateGeneration) return;
            this.wallet.set(state);
            if (this.needsRefresh()) this.operationError.set('');
            this.needsRefresh.set(false);
            this.remember(id);
        } catch (error: unknown) {
            if (selection === this.selectionGeneration && generation === this.stateGeneration) this.stateError.set(describeError(error));
        } finally {
            if (selection === this.selectionGeneration && generation === this.stateGeneration) this.stateLoading.set(false);
        }
    }

    private async pollReadSide(): Promise<void> {
        if (!this.selected() || this.readPollInFlight || this.busy()) return;
        this.readPollInFlight = true;
        try {
            await Promise.all([this.loadState(), this.loadHandler()]);
            this.comparisonContext.update(value => value + 1);
        } finally {
            this.readPollInFlight = false;
        }
    }

    async toggleHandler(): Promise<void> {
        const current = this.handler();
        if (!current) return;
        this.handlerError.set('');
        try {
            this.handler.set(current.paused || current.pauseRequested
                ? await this.api.resumeProjectionHandler() : await this.api.pauseProjectionHandler());
        } catch (error: unknown) {
            this.handlerError.set(describeError(error));
        }
    }

    onComparison(result: WalletComparison | null): void {
        this.comparisonLagging.set(result !== null && (result.status === 'LAGGING' || result.readModel === null));
    }

    private async loadHandler(): Promise<void> {
        try {
            this.handler.set(await this.api.getProjectionHandler());
            this.handlerError.set('');
        } catch (error: unknown) {
            this.handlerError.set(describeError(error));
        }
    }

    ngOnInit(): void {
        void this.loadHandler();
    }

    ngOnDestroy(): void {
        window.clearInterval(this.readPollTimer);
    }

    private readRecent(): readonly RecentWallet[] {
        try {
            const stored: unknown = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]');
            if (!Array.isArray(stored)) return [];
            // В localStorage лежат только UUID; ответы команд, баланс и история туда не записываются.
            return [...new Set(stored.filter((id): id is string => typeof id === 'string' && UUID_PATTERN.test(id))
                .map(id => id.toLowerCase()))].slice(0, 12).map(id => ({ id }));
        } catch {
            this.storageMessage.set('Локальная история недоступна. Кошельки можно открывать по UUID.');
            return [];
        }
    }

    private remember(id: string): void {
        const recent = [{ id }, ...this.recent().filter(item => item.id !== id)].slice(0, 12);
        this.recent.set(recent);
        try { localStorage.setItem(RECENT_KEY, JSON.stringify(recent.map(item => item.id))); }
        catch { this.storageMessage.set('Браузер запретил сохранение списка. Недавние кошельки доступны до закрытия вкладки.'); }
    }
}
