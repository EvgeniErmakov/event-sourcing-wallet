import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { WalletEvent } from '../api/wallet.models';
import { formatMoney } from '../shared/numbers';

const EVENT_NAMES: Readonly<Record<string, string>> = {
    WalletCreated: 'Кошелёк создан', MoneyDeposited: 'Пополнение', MoneyWithdrawn: 'Списание', WalletClosed: 'Кошелёк закрыт',
};

@Component({
    selector: 'app-event-history',
    standalone: true,
    imports: [DatePipe, JsonPipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <section class="card history-card" aria-labelledby="history-heading" [attr.aria-busy]="loading()">
            <div class="card-heading">
                <div class="section-heading"><span class="step">04</span><h2 id="history-heading">История событий</h2></div>
                <span class="count">Загружено: {{ events().length }}</span>
            </div>
            <p class="hint">Факты из event store, по возрастанию версии. По 20 событий на страницу.</p>
            @if (!selected()) {
                <div class="empty-small">Выберите кошелёк, чтобы увидеть его события.</div>
            } @else {
                <ol class="event-list">
                    @for (event of events(); track event.eventId) {
                        <li class="event-item">
                            <div class="event-version" [title]="'Версия ' + event.streamVersion">{{ event.streamVersion }}</div>
                            <div class="event-content">
                                <div class="event-heading"><h3>{{ eventName(event.eventType) }}</h3>
                                    <time [attr.datetime]="event.occurredAt">{{ event.occurredAt | date:'dd.MM.yyyy HH:mm:ss' }}</time>
                                </div>
                                <p class="event-description">{{ description(event) }}</p>
                                <code class="event-type">{{ event.eventType }}</code>
                                <details class="event-details">
                                    <summary>JSON и идентификаторы</summary>
                                    <dl class="technical-list">
                                        <dt>eventId</dt><dd>{{ event.eventId }}</dd>
                                        <dt>walletId</dt><dd>{{ event.walletId }}</dd>
                                        <dt>commandId</dt><dd>{{ event.commandId }}</dd>
                                        <dt>streamVersion</dt><dd>{{ event.streamVersion }}</dd>
                                        <dt>schemaVersion</dt><dd>{{ event.schemaVersion }}</dd>
                                        <dt>occurredAt</dt><dd>{{ event.occurredAt }}</dd>
                                    </dl>
                                    <pre>{{ event.payload | json }}</pre>
                                </details>
                            </div>
                        </li>
                    }
                </ol>
                @if (error()) { <div class="notice error" role="alert">{{ error() }}
                    <button class="text-button" type="button" [disabled]="loading() || busy()" (click)="retry.emit()">
                        Повторить загрузку
                    </button>
                </div> }
                @if (loading()) { <p class="hint" role="status">Загружаем события…</p> }
                @if (!loading() && !error() && loaded()) {
                    @if (hasMore()) {
                        <p class="hint">Это часть истории. На сервере есть следующие события.</p>
                        <button type="button" class="secondary full-width" [disabled]="busy()" (click)="more.emit()">
                            Загрузить ещё
                        </button>
                    } @else {
                        <p class="history-end">{{ events().length ? 'Достигнут конец истории на момент чтения.' : 'Событий не получено.' }}</p>
                    }
                }
            }
        </section>
    `,
})
export class EventHistoryComponent {
    readonly events = input.required<readonly WalletEvent[]>();
    readonly selected = input.required<string | null>();
    readonly loading = input(false);
    readonly loaded = input(false);
    readonly busy = input(false);
    readonly hasMore = input(false);
    readonly error = input('');
    readonly more = output<void>();
    readonly retry = output<void>();

    eventName(type: string): string { return EVENT_NAMES[type] ?? type; }

    description(event: WalletEvent): string {
        const amount = event.payload['amountMinor'];
        switch (event.eventType) {
            case 'WalletCreated': return `Валюта: ${String(event.payload['currency'] ?? 'не указана')}`;
            case 'MoneyDeposited': return typeof amount === 'number' ? `Зачислено ${formatMoney(amount)}` : 'Смотрите payload';
            case 'MoneyWithdrawn': return typeof amount === 'number' ? `Списано ${formatMoney(amount)}` : 'Смотрите payload';
            case 'WalletClosed': return 'Зафиксировано закрытие кошелька';
            default: return 'Описание этого типа неизвестно UI. Исходный payload доступен ниже.';
        }
    }
}
