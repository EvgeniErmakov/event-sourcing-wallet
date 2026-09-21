import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { describeError, WalletApiService } from '../api/wallet-api.service';
import { WalletComparison } from '../api/wallet.models';
import { formatMoney } from '../shared/numbers';

/** Диагностика только по нажатию: не заменяет текущую карточку и не служит источником команд. */
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
                Событие и модель чтения фиксируются одной транзакцией.</p>
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
                            <p class="error-text">Проекция отсутствует. Нарушена целостность данных.</p>
                        }
                    </article>
                </div>
                <p class="notice" [class.success]="result.matches" [class.error]="!result.matches" role="status">
                    {{ result.matches ? 'Модели совпадают.' : 'Модели не совпадают — проблема целостности проекции.' }}
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
    readonly walletId = input.required<string | null>();
    readonly context = input.required<number>();
    readonly busy = input(false);
    readonly comparison = signal<WalletComparison | null>(null);
    readonly loading = signal(false);
    readonly error = signal('');
    readonly money = formatMoney;

    constructor() {
        effect(() => {
            this.walletId();
            this.context();
            ++this.generation;
            this.comparison.set(null);
            this.error.set('');
            this.loading.set(false);
        });
    }

    /** Счётчик выбора и запроса защищает в том числе переключение A → B → A и обновление после команды. */
    async refresh(): Promise<void> {
        const id = this.walletId();
        if (!id || this.loading() || this.busy()) return;
        const generation = ++this.generation;
        const context = this.context();
        const current = (): boolean => generation === this.generation && context === this.context() && id === this.walletId();
        this.loading.set(true);
        this.error.set('');
        this.comparison.set(null);
        try {
            const comparison = await this.api.getComparison(id);
            if (current()) this.comparison.set(comparison);
        } catch (error: unknown) {
            if (current()) this.error.set(describeError(error));
        } finally {
            if (current()) this.loading.set(false);
        }
    }
}
