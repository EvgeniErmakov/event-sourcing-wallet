import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { WalletState } from '../api/wallet.models';
import { formatMoney } from '../shared/numbers';

@Component({
    selector: 'app-wallet-state',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <section class="card balance-card" aria-labelledby="state-heading" [attr.aria-busy]="loading()">
            <div class="card-heading">
                <div class="section-heading"><span class="step">02</span><h2 id="state-heading">Текущее состояние</h2></div>
                <button class="text-button" type="button" [disabled]="!selected() || loading() || busy()" (click)="refresh.emit()">
                    <span aria-hidden="true">↻</span> Обновить
                </button>
            </div>
            @if (selected(); as id) {
                <div class="id-line"><code>{{ id }}</code>
                    <button class="copy-button" type="button" (click)="copy(id)" aria-label="Копировать UUID">Копировать</button>
                </div>
                @if (copyMessage()) { <p class="hint" role="status">{{ copyMessage() }}</p> }
            }
            @if (loading() || (busy() && selected() && !wallet() && !error())) {
                <div class="state-placeholder" role="status"><span class="loading-dot"></span> Восстанавливаем состояние на сервере…</div>
            } @else if (error()) {
                <div class="notice error" role="alert">{{ error() }}</div>
            } @else if (wallet(); as state) {
                <p class="eyebrow">Баланс кошелька</p>
                <div class="balance">{{ money(state.balanceMinor) }}</div>
                <div class="state-meta">
                    <span class="badge" [class.closed]="state.status === 'CLOSED'">
                        <span class="status-dot" aria-hidden="true"></span>{{ state.status === 'ACTIVE' ? 'Активен' : 'Закрыт' }}
                    </span>
                    <span>Валюта <strong>{{ state.currency }}</strong></span>
                    <span>Версия <strong class="version-number">{{ state.version }}</strong></span>
                </div>
                <p class="state-note">Состояние восстановлено из событий · GET /api/wallets/…</p>
            } @else {
                <div class="state-placeholder"><span class="empty-symbol" aria-hidden="true">↗</span>
                    <h3>Начните с кошелька</h3><p>Создайте новый или откройте существующий по UUID.</p>
                </div>
            }
        </section>
    `,
})
export class WalletStateComponent {
    readonly wallet = input.required<WalletState | null>();
    readonly selected = input.required<string | null>();
    readonly loading = input(false);
    readonly busy = input(false);
    readonly error = input('');
    readonly refresh = output<void>();
    readonly copyMessage = signal('');
    readonly money = formatMoney;

    async copy(id: string): Promise<void> {
        try {
            await navigator.clipboard.writeText(id);
            this.copyMessage.set('UUID скопирован.');
        } catch {
            this.copyMessage.set('Копирование недоступно. Выделите UUID и скопируйте вручную.');
        }
    }
}
