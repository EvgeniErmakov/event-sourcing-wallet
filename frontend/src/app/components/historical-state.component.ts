import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { describeError, WalletApiService } from '../api/wallet-api.service';
import { WalletState } from '../api/wallet.models';
import { formatMoney, parseVersion } from '../shared/numbers';

/** Отдельное состояние просмотра прошлого; оно никогда не передаётся в компонент команд. */
@Component({
    selector: 'app-historical-state',
    standalone: true,
    imports: [ReactiveFormsModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <section class="card" aria-labelledby="past-heading" [attr.aria-busy]="loading()">
            <div class="section-heading"><span class="step">05</span><h2 id="past-heading">Вернуться к версии</h2></div>
            <p class="hint">Посмотрите, каким был кошелёк после выбранного события. Текущее состояние не изменится.</p>
            <form [formGroup]="form" (ngSubmit)="show()">
                <label for="at-version">Номер версии</label>
                <div class="inline-form">
                    <input id="at-version" [formControl]="version" inputmode="numeric" placeholder="Например, 2"
                        autocomplete="off" aria-describedby="past-error">
                    <button class="secondary" type="submit" [disabled]="!walletId() || loading()">Показать состояние</button>
                </div>
            </form>
            @if (loading()) { <p class="hint" role="status">Восстанавливаем выбранную версию…</p> }
            @if (error()) { <p id="past-error" class="notice error" role="alert">{{ error() }}</p> }
            @if (state(); as past) {
                <article class="past-result" aria-live="polite">
                    <span class="eyebrow">Исторический просмотр</span>
                    <h3>Состояние на версии {{ past.version }}</h3>
                    <div class="past-balance">{{ money(past.balanceMinor) }}</div>
                    <p>{{ past.currency }} · {{ past.status === 'ACTIVE' ? 'Активен' : 'Закрыт' }}</p>
                    <p class="hint">Только просмотр. Команды используют версию из текущей карточки.</p>
                </article>
            }
        </section>
    `,
})
export class HistoricalStateComponent {
    private readonly api = inject(WalletApiService);
    private requestGeneration = 0;
    readonly walletId = input.required<string | null>();
    readonly version = new FormControl('', { nonNullable: true });
    readonly form = new FormGroup({ version: this.version });
    readonly state = signal<WalletState | null>(null);
    readonly loading = signal(false);
    readonly error = signal('');
    readonly money = formatMoney;

    constructor() {
        effect(() => {
            this.walletId();
            ++this.requestGeneration;
            this.state.set(null);
            this.error.set('');
            this.loading.set(false);
            this.version.setValue('');
        });
    }

    async show(): Promise<void> {
        const id = this.walletId();
        if (!id || this.loading()) return;
        this.error.set('');
        this.state.set(null);
        let atVersion: number;
        try { atVersion = parseVersion(this.version.value); }
        catch (error: unknown) { this.error.set(describeError(error)); return; }
        const generation = ++this.requestGeneration;
        this.loading.set(true);
        try {
            const state = await this.api.getWallet(id, atVersion);
            // Даже A → B → A не даёт старому ответу права обновить новый просмотр A.
            if (generation === this.requestGeneration && id === this.walletId()) {
                if (state.version !== atVersion) throw new Error('Ответ сервера не соответствует запрошенной версии.');
                this.state.set(state);
            }
        } catch (error: unknown) {
            if (generation === this.requestGeneration && id === this.walletId()) this.error.set(describeError(error));
        } finally {
            if (generation === this.requestGeneration && id === this.walletId()) this.loading.set(false);
        }
    }
}
