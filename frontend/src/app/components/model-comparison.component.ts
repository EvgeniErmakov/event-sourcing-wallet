import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core';
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
            @if (loading()) { <p class="hint" role="status">Сравниваем модели в одном снимке…</p> }
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
                <p class="hint">Результат на момент нажатия. После внешних изменений обновите сравнение вручную.</p>
            } @else if (!loading() && !error()) {
                <p class="empty-small">{{ walletId() ? 'Нажмите «Обновить сравнение» для чтения обеих моделей.' : 'Выберите кошелёк.' }}</p>
            }
        </section>
    `,
})
export class ModelComparisonComponent {
    private readonly api = inject(WalletApiService);
    private generation = 0;
    private requestInFlight = false;
    readonly walletId = input.required<string | null>();
    readonly context = input.required<number>();
    readonly busy = input(false);
    readonly comparison = signal<WalletComparison | null>(null);
    readonly loading = signal(false);
    readonly error = signal('');
    readonly stateChange = output<WalletComparison | null>();
    readonly money = formatMoney;

    constructor() {
        effect(() => {
            this.walletId();
            this.context();
            ++this.generation;
            this.comparison.set(null);
            this.error.set('');
            if (!this.requestInFlight) this.loading.set(false);
            void this.refresh();
        });
    }

    /** Счётчик выбора и запроса защищает в том числе переключение A → B → A и обновление после команды. */
    async refresh(): Promise<void> {
        const id = this.walletId();
        if (!id || this.requestInFlight || this.busy()) return;
        const generation = ++this.generation;
        const context = this.context();
        const current = (): boolean => generation === this.generation && context === this.context() && id === this.walletId();
        this.loading.set(true);
        this.requestInFlight = true;
        this.error.set('');
        this.comparison.set(null);
        try {
            const comparison = await this.api.getComparison(id);
            if (current()) {
                this.comparison.set(comparison);
                this.stateChange.emit(comparison);
            }
        } catch (error: unknown) {
            if (current()) this.error.set(describeError(error));
        } finally {
            this.requestInFlight = false;
            if (current()) this.loading.set(false);
        }
    }
}
