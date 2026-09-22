import { ChangeDetectionStrategy, Component, effect, inject, input, OnDestroy, output, signal } from '@angular/core';
import { describeError, WalletApiService } from '../api/wallet-api.service';
import { WalletComparison } from '../api/wallet.models';
import { formatMoney } from '../shared/numbers';

/** Диагностика читает обе модели периодически и по кнопке; она не служит источником команд. */
@Component({
    selector: 'app-model-comparison',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <section class="card" aria-labelledby="comparison-heading" [attr.aria-busy]="loading()">
            <div class="card-heading">
                <h2 id="comparison-heading">Сравнение моделей</h2>
                <button class="secondary" type="button" [disabled]="!walletId() || loading() || busy()" (click)="refresh()">
                    Обновить сравнение
                </button>
            </div>
            <p class="hint">Команды проверяют состояние из событий. Обычное чтение использует отдельную таблицу.
                Событие и receipt фиксируются командой, проекция догоняет их отдельной транзакцией.</p>
            @if (loading()) { <p class="hint" role="status">Сверяем проекцию с историей на её бизнес-версии…</p> }
            @if (error()) { <p class="notice error" role="alert">{{ error() }}</p> }
            @if (comparison(); as result) {
                <div class="comparison-grid">
                    <article>
                        <h3>Состояние из событий</h3>
                        <p class="past-balance">{{ money(result.eventState.balanceMinor) }}</p>
                        <p>{{ result.eventState.currency }} · {{ result.eventState.status }} · версия {{ result.eventState.version }}</p>
                    </article>
                    <article>
                        <h3>Состояние из таблицы чтения</h3>
                        @if (result.readModel; as model) {
                            <p class="past-balance">{{ money(model.balanceMinor) }}</p>
                            <p>{{ model.currency }} · {{ model.status }} · версия {{ model.version }}</p>
                        } @else {
                            <p class="error-text">Проекция ещё не создана обработчиком.</p>
                        }
                    </article>
                </div>
                <p class="hint">Версия потока: <strong>{{ result.streamVersion }}</strong> · версия проекции:
                    <strong>{{ result.projectionVersion }}</strong> · ожидают обработки: <strong>{{ result.pendingEvents }}</strong></p>
                <p class="notice" [class.success]="result.matches" [class.warning]="result.status === 'LAGGING'" [class.error]="!result.matches && result.status !== 'LAGGING'" role="status">
                    {{ result.matches ? 'Модели совпадают.' : result.status === 'LAGGING' ? 'Проекция догоняет.' : 'Ошибка целостности моделей.' }}
                </p>
                <p class="hint">Последнее сравнение по бизнес-версиям; чтение моделей выполняется последовательно. Обновление выполняется автоматически и доступно по кнопке.</p>
            } @else if (!loading() && !error()) {
                <p class="empty-small">{{ walletId() ? 'Ожидаем первое сравнение моделей.' : 'Выберите кошелёк.' }}</p>
            }
        </section>
    `,
})
export class ModelComparisonComponent implements OnDestroy {
    private readonly api = inject(WalletApiService);
    private walletGeneration = 0;
    private activeRequest: ComparisonRequest | null = null;
    private lastWalletId: string | null | undefined;
    private lastPollingContext = 0;
    private lastManualContext = 0;
    private destroyed = false;
    readonly walletId = input.required<string | null>();
    readonly context = input.required<number>();
    readonly pollingContext = input(0);
    readonly busy = input(false);
    readonly comparison = signal<WalletComparison | null>(null);
    readonly loading = signal(false);
    readonly error = signal('');
    readonly stateChange = output<WalletComparison | null>();
    readonly money = formatMoney;

    constructor() {
        effect(() => {
            const id = this.walletId();
            const manualContext = this.context();
            const pollingContext = this.pollingContext();
            const polling = pollingContext !== this.lastPollingContext && manualContext === this.lastManualContext;
            this.lastPollingContext = pollingContext;
            this.lastManualContext = manualContext;
            if (id !== this.lastWalletId) {
                this.lastWalletId = id;
                ++this.walletGeneration;
                this.comparison.set(null);
                this.error.set('');
                this.loading.set(false);
            }
            this.requestRefresh(id, !polling);
        });
    }

    /**
     * Контекст кошелька меняется только при выборе другого UUID. Повторный сигнал того же
     * контекста ставит максимум одну отложенную загрузку и не делает текущий ответ устаревшим.
     */
    async refresh(): Promise<void> {
        const id = this.walletId();
        this.requestRefresh(id, true);
    }

    private requestRefresh(id: string | null, queueIfBusy: boolean): void {
        if (!id) return;
        const current = this.activeRequest;
        if (current && current.walletId === id && current.walletGeneration === this.walletGeneration) {
            if (queueIfBusy) current.queued = true;
            return;
        }
        const request: ComparisonRequest = {
            walletId: id,
            walletGeneration: this.walletGeneration,
            queued: false,
        };
        this.activeRequest = request;
        void this.run(request);
    }

    private async run(request: ComparisonRequest): Promise<void> {
        this.loading.set(true);
        this.error.set('');
        const isCurrent = (): boolean => !this.destroyed && this.activeRequest === request
            && request.walletGeneration === this.walletGeneration
            && request.walletId === this.walletId();
        try {
            const comparison = await this.api.getComparison(request.walletId);
            if (isCurrent()) {
                this.comparison.set(comparison);
                this.stateChange.emit(comparison);
            }
        } catch (error: unknown) {
            if (isCurrent()) this.error.set(describeError(error));
        } finally {
            if (isCurrent()) {
                this.loading.set(false);
                if (request.queued) {
                    request.queued = false;
                    void this.run(request);
                } else if (this.activeRequest === request) {
                    this.activeRequest = null;
                }
            }
        }
    }

    ngOnDestroy(): void {
        this.destroyed = true;
    }
}

interface ComparisonRequest {
    readonly walletId: string;
    readonly walletGeneration: number;
    queued: boolean;
}
