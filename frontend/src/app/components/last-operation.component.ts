import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { LastOperation } from '../api/wallet.models';
import { displayJson } from '../shared/numbers';

@Component({
    selector: 'app-last-operation',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <details class="card last-operation" open>
            <summary class="operation-summary"><span>Последняя операция</span>
                <span class="summary-state">{{ operation()?.pending ? 'Отправляется' : operation() ? operation()?.request?.kind : 'Пока нет команд' }}</span>
            </summary>
            <p class="hint">Новая команда проверяется на восстановленном состоянии. При успехе сервер сохраняет новое событие
                вместе с проекцией и результатом команды в одной транзакции.
                Здесь показаны только отправленный запрос и полученный ответ.</p>
            @if (operation(); as last) {
                <dl class="technical-list request-meta">
                    <dt>Команда</dt><dd>{{ last.request.kind }} {{ last.repeated ? '· повтор' : '' }}</dd>
                    <dt>Кошелёк</dt><dd>{{ last.request.walletId }}</dd>
                    <dt>Запрос</dt><dd>{{ last.request.method }} {{ last.request.url }}</dd>
                    <dt>Idempotency-Key</dt><dd>{{ last.request.key }}</dd>
                    <dt>expectedVersion</dt><dd>{{ last.request.expectedVersion ?? 'Не передаётся при создании' }}</dd>
                    <dt>HTTP-статус</dt><dd>{{ last.status ?? (last.pending ? 'Ожидание ответа' : 'Ответ не получен') }}</dd>
                </dl>
                <div class="payload-grid">
                    <div><h3>Тело запроса</h3><pre>{{ json(last.request.body) }}</pre></div>
                    <div><h3>Тело ответа</h3><pre>{{ last.responseBody === null ? 'Ответ не получен' : json(last.responseBody) }}</pre>
                        <p class="hint">Числа вне диапазона UI сохраняются здесь в исходном HTTP-тексте, без преобразования.</p>
                    </div>
                </div>
                @if (last.message) {
                    <p class="notice" [class.warning]="last.uncertain" role="status">{{ last.message }}</p>
                }
                <div class="repeat-row">
                    <button class="secondary" type="button" [disabled]="busy() || last.pending" (click)="repeat.emit()">
                        Повторить тот же запрос
                    </button>
                    <p class="hint">Те же метод, URL, тело и ключ. Повтор успешной команды возвращает первоначальный результат
                        и не создаёт новое событие. Актуальное состояние загружается отдельно.</p>
                </div>
                <p class="hint">Запрос хранится в памяти вкладки. При неизвестном результате не перезагружайте страницу до повтора.</p>
            } @else {
                <p class="empty-small">Создайте кошелёк или выполните операцию — здесь появятся запрос и ответ сервера.</p>
            }
        </details>
    `,
})
export class LastOperationComponent {
    readonly operation = input.required<LastOperation | null>();
    readonly busy = input(false);
    readonly repeat = output<void>();
    readonly json = displayJson;
}
