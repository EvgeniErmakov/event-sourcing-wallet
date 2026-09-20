import { ChangeDetectionStrategy, Component, effect, input, output, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { RecentWallet } from '../api/wallet.models';
import { UUID_PATTERN } from '../shared/numbers';

@Component({
    selector: 'app-wallet-selector',
    standalone: true,
    imports: [ReactiveFormsModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <section class="card selector-card" aria-labelledby="selection-heading">
            <div class="section-heading"><span class="step">01</span><h2 id="selection-heading">Выбор кошелька</h2></div>
            <button class="primary full-width" type="button" [disabled]="createDisabled()" (click)="create.emit()">
                <span aria-hidden="true">＋</span> Создать кошелёк
            </button>
            <p class="hint">Новый UUID и валюта RUB. Начальный баланс — 0 ₽.</p>
            <div class="divider"><span>или откройте существующий</span></div>
            <form (ngSubmit)="openWallet()">
                <label for="wallet-id">UUID кошелька</label>
                <input id="wallet-id" class="mono" [formControl]="walletId" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                    autocomplete="off" spellcheck="false" [attr.aria-invalid]="invalid()" aria-describedby="uuid-error">
                @if (invalid()) { <p id="uuid-error" class="error-text" role="alert">Введите UUID в формате 8-4-4-4-12.</p> }
                <button class="secondary full-width" type="submit">Открыть</button>
            </form>
            <div class="recent-heading"><h3>Недавно открытые</h3><span class="count">{{ recent().length }}</span></div>
            <p class="hint">Только в этом браузере. Это не список всех кошельков сервера.</p>
            <div class="recent-list">
                @for (item of recent(); track item.id) {
                    <button type="button" class="recent-item" [class.selected]="selected() === item.id"
                        [attr.aria-current]="selected() === item.id ? 'true' : null" (click)="open.emit(item.id)" [title]="item.id">
                        <span class="wallet-mark" aria-hidden="true">W</span>
                        <span class="mono truncate">{{ item.id }}</span><span aria-hidden="true">↗</span>
                    </button>
                } @empty { <p class="empty-small">Открытые кошельки появятся здесь.</p> }
            </div>
        </section>
    `,
})
export class WalletSelectorComponent {
    readonly recent = input.required<readonly RecentWallet[]>();
    readonly selected = input.required<string | null>();
    readonly createDisabled = input(false);
    readonly create = output<void>();
    readonly open = output<string>();
    readonly invalid = signal(false);
    readonly walletId = new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(UUID_PATTERN)] });

    constructor() {
        effect(() => {
            this.walletId.setValue(this.selected() ?? '');
            this.invalid.set(false);
        });
    }

    openWallet(): void {
        const id = this.walletId.value.trim().toLowerCase();
        this.walletId.setValue(id);
        this.invalid.set(this.walletId.invalid);
        if (this.walletId.valid) this.open.emit(id);
    }
}
