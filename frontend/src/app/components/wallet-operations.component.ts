import { ChangeDetectionStrategy, Component, effect, input, output, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MoneyIntent, MoneyOperation, WalletState } from '../api/wallet.models';
import { describeError } from '../api/wallet-api.service';
import { MAX_SAFE_VALUE, parseRubles } from '../shared/numbers';

@Component({
    selector: 'app-wallet-operations',
    standalone: true,
    imports: [ReactiveFormsModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <section class="card" aria-labelledby="operations-heading" [attr.aria-busy]="busy()">
            <div class="section-heading"><span class="step">03</span><h2 id="operations-heading">Операции</h2></div>
            <form (ngSubmit)="submit()">
                <fieldset [disabled]="disabled()">
                    <legend class="sr-only">Выберите операцию и сумму</legend>
                    <div class="segmented" aria-label="Операция">
                        <button type="button" [class.active]="operation() === 'deposits'"
                            [attr.aria-pressed]="operation() === 'deposits'" (click)="setOperation('deposits')">＋ Пополнить</button>
                        <button type="button" [class.active]="operation() === 'withdrawals'"
                            [attr.aria-pressed]="operation() === 'withdrawals'" (click)="setOperation('withdrawals')">− Списать</button>
                    </div>
                    <label for="amount">Сумма в рублях</label>
                    <div class="amount-field"><input id="amount" inputmode="decimal" [formControl]="amount"
                        placeholder="0,00" autocomplete="off" aria-describedby="amount-hint amount-error"><span>₽</span></div>
                    <p id="amount-hint" class="hint">Точка или запятая, до двух знаков после неё.</p>
                    <button type="submit" class="primary full-width">
                        {{ busy() ? 'Отправляем…' : operation() === 'deposits' ? 'Пополнить кошелёк' : 'Списать с кошелька' }}
                    </button>
                </fieldset>
            </form>
            @if (validationError()) { <p id="amount-error" class="error-text" role="alert">{{ validationError() }}</p> }
            @if (error()) { <div class="notice error" role="alert">{{ error() }}
                @if (needsRefresh()) {
                    <button type="button" class="text-button" [disabled]="busy()" (click)="refresh.emit()">Обновить состояние</button>
                }
            </div> }
            @if (message()) { <p class="notice success" role="status">{{ message() }}</p> }
            @if (blockedReason()) { <p class="hint">{{ blockedReason() }}</p> }
            @if (wallet(); as state) {
                <p class="hint mono">expectedVersion: {{ state.version }} · из текущего состояния</p>
                <div class="close-section">
                    @if (confirmClose()) {
                        <div class="notice warning" role="group" aria-label="Подтверждение закрытия">
                            <p>Закрыть кошелёк? После закрытия пополнение и списание недоступны.</p>
                            <div class="button-row">
                                <button class="danger" type="button" [disabled]="disabled() || state.balanceMinor !== 0"
                                    (click)="close.emit(); confirmClose.set(false)">Да, закрыть</button>
                                <button class="secondary" type="button" (click)="confirmClose.set(false)">Отмена</button>
                            </div>
                        </div>
                    } @else {
                        <button class="text-button danger-text" type="button" [disabled]="disabled() || state.balanceMinor !== 0"
                            (click)="confirmClose.set(true)">Закрыть кошелёк</button>
                        <span class="hint">Только при нулевом балансе</span>
                    }
                </div>
            }
        </section>
    `,
})
export class WalletOperationsComponent {
    readonly wallet = input.required<WalletState | null>();
    readonly disabled = input(true);
    readonly busy = input(false);
    readonly blockedReason = input('');
    readonly error = input('');
    readonly message = input('');
    readonly needsRefresh = input(false);
    readonly money = output<MoneyIntent>();
    readonly close = output<void>();
    readonly refresh = output<void>();
    readonly operation = signal<MoneyOperation>('deposits');
    readonly amount = new FormControl('', { nonNullable: true });
    readonly confirmClose = signal(false);
    readonly validationError = signal('');

    constructor() {
        effect(() => {
            this.wallet();
            this.confirmClose.set(false);
            this.validationError.set('');
        });
    }

    setOperation(operation: MoneyOperation): void {
        this.operation.set(operation);
        this.validationError.set('');
    }

    submit(): void {
        if (this.disabled()) return;
        this.validationError.set('');
        try {
            const amountMinor = parseRubles(this.amount.value);
            const state = this.wallet();
            if (!state) return;
            if (this.operation() === 'deposits' && BigInt(state.balanceMinor) + BigInt(amountMinor) > BigInt(MAX_SAFE_VALUE)) {
                throw new Error('После пополнения баланс выйдет за безопасный диапазон UI. Уменьшите сумму.');
            }
            this.money.emit({ operation: this.operation(), amountMinor });
        } catch (error: unknown) {
            this.validationError.set(describeError(error));
        }
    }
}
