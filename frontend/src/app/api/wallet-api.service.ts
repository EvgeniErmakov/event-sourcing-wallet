import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import { EventPage, ProblemDetail, ProjectionHandlerStatus, SavedCommand, WalletComparison, WalletEvent, WalletState } from './wallet.models';
import { NumericRangeError, parseSafeJson, UUID_PATTERN } from '../shared/numbers';

export class ApiFailure extends Error {
    constructor(
        message: string,
        readonly status: number,
        readonly code: string | null,
        readonly rawBody: string | null,
    ) { super(message); }
}

const MESSAGES: Readonly<Record<string, string>> = {
    WALLET_NOT_FOUND: 'Кошелёк не найден. Проверьте UUID или создайте новый кошелёк.',
    VERSION_NOT_FOUND: 'Такой версии кошелька пока нет.',
    WALLET_ALREADY_EXISTS: 'Кошелёк с таким UUID уже существует. Откройте его.',
    VERSION_CONFLICT: 'Версия кошелька изменилась. Обновите состояние перед новой командой.',
    IDEMPOTENCY_KEY_REUSED: 'Этот ключ уже использован с другим содержанием запроса.',
    WALLET_CLOSED: 'Кошелёк закрыт. Операции с ним недоступны.',
    INSUFFICIENT_FUNDS: 'На кошельке недостаточно средств для списания.',
    NON_ZERO_BALANCE: 'Для закрытия кошелька баланс должен быть равен нулю.',
    BALANCE_OVERFLOW: 'Сумма превышает допустимый баланс сервера.',
    INVALID_REQUEST: 'Сервер отклонил параметры запроса. Проверьте введённые значения.',
    CORRUPT_HISTORY: 'Сервер обнаружил ошибку целостности истории.',
    PROJECTION_INTEGRITY_ERROR: 'Модель чтения отсутствует или повреждена. Нарушена целостность данных; проверьте журнал сервера.',
    PROJECTION_NOT_READY: 'Кошелёк создан, проекция ещё не готова. Подождите обработки событий.',
    INTERNAL_ERROR: 'Внутренняя ошибка сервера. Подробности доступны в его журнале.',
};

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function safeInteger(value: unknown, min: number): value is number {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= min;
}

function isUuid(value: unknown): value is string {
    return typeof value === 'string' && UUID_PATTERN.test(value);
}

function invalidResponse(): Error {
    return new Error('Сервер вернул неподдерживаемый формат ответа. Состояние не используется для команд.');
}

export function decodeWallet(raw: string, id: string): WalletState {
    return decodeWalletValue(parseSafeJson(raw), id);
}

function decodeWalletValue(data: unknown, id: string): WalletState {
    if (!isRecord(data) || data['walletId'] !== id || data['currency'] !== 'RUB'
        || !['ACTIVE', 'CLOSED'].includes(String(data['status']))
        || !safeInteger(data['balanceMinor'], 0) || !safeInteger(data['version'], 1)) throw invalidResponse();
    return data as unknown as WalletState;
}

/** Сначала проверяются все JSON-числа, затем обе независимые модели и согласованность признака совпадения. */
function decodeComparison(raw: string, id: string): WalletComparison {
    const data = parseSafeJson(raw);
    if (!isRecord(data) || typeof data['matches'] !== 'boolean' || !safeInteger(data['streamVersion'], 1)
        || !safeInteger(data['projectionVersion'], 0) || !safeInteger(data['pendingEvents'], 0)
        || !['MATCHED', 'LAGGING'].includes(String(data['status']))) throw invalidResponse();
    const eventState = decodeWalletValue(data['eventState'], id);
    const readModel = data['readModel'] === null ? null : decodeWalletValue(data['readModel'], id);
    if (data['streamVersion'] !== eventState.version
        || (readModel !== null && data['projectionVersion'] !== readModel.version)) throw invalidResponse();
    const matches = readModel !== null && eventState.balanceMinor === readModel.balanceMinor
        && eventState.currency === readModel.currency && eventState.status === readModel.status
        && eventState.version === readModel.version;
    if (matches !== data['matches'] || data['pendingEvents'] !== data['streamVersion'] - data['projectionVersion']) {
        throw invalidResponse();
    }
    return { eventState, readModel, streamVersion: data['streamVersion'], projectionVersion: data['projectionVersion'],
        pendingEvents: data['pendingEvents'], status: data['status'] as WalletComparison['status'], matches };
}

function decodeHandlerStatus(raw: string): ProjectionHandlerStatus {
    const data = parseSafeJson(raw);
    if (!isRecord(data) || !['RUNNING', 'PAUSE_REQUESTED', 'PAUSED', 'IDLE'].includes(String(data['status']))
        || typeof data['paused'] !== 'boolean' || typeof data['pauseRequested'] !== 'boolean'
        || typeof data['processing'] !== 'boolean'
        || (data['lastError'] !== null && typeof data['lastError'] !== 'string')) throw invalidResponse();
    return data as unknown as ProjectionHandlerStatus;
}

function decodeEvents(raw: string, id: string, afterVersion: number): EventPage {
    const data = parseSafeJson(raw);
    if (!isRecord(data) || !Array.isArray(data['items']) || typeof data['hasMore'] !== 'boolean'
        || !safeInteger(data['nextAfterVersion'], 0)) throw invalidResponse();
    const items: WalletEvent[] = [];
    let previous = afterVersion;
    for (const item of data['items'] as unknown[]) {
        if (!isRecord(item) || item['walletId'] !== id || !isUuid(item['eventId']) || !isUuid(item['commandId'])
            || !safeInteger(item['streamVersion'], 1) || item['streamVersion'] <= previous
            || !safeInteger(item['schemaVersion'], 1) || typeof item['eventType'] !== 'string'
            || typeof item['occurredAt'] !== 'string' || Number.isNaN(Date.parse(item['occurredAt']))
            || !isRecord(item['payload'])) throw invalidResponse();
        previous = item['streamVersion'];
        items.push(item as unknown as WalletEvent);
    }
    if (data['nextAfterVersion'] !== previous || (data['hasMore'] && items.length === 0)) throw invalidResponse();
    return { items, nextAfterVersion: previous, hasMore: data['hasMore'] };
}

export function describeError(error: unknown): string {
    return error instanceof Error ? error.message : 'Не удалось выполнить запрос.';
}

/** Единственная точка HTTP-доступа. Никаких автоматических повторов изменяющих запросов. */
@Injectable({ providedIn: 'root' })
export class WalletApiService {
    private readonly http = inject(HttpClient);

    async getWallet(id: string, atVersion?: number): Promise<WalletState> {
        const url = `/api/wallets/${id}${atVersion === undefined ? '' : `?atVersion=${atVersion}`}`;
        const response = await this.request('GET', url);
        return decodeWallet(response.body ?? '', id);
    }

    async getEvents(id: string, afterVersion: number): Promise<EventPage> {
        const response = await this.request('GET', `/api/wallets/${id}/events?afterVersion=${afterVersion}&limit=20`);
        return decodeEvents(response.body ?? '', id, afterVersion);
    }

    async getComparison(id: string): Promise<WalletComparison> {
        const response = await this.request('GET', `/api/wallets/${id}/comparison`);
        return decodeComparison(response.body ?? '', id);
    }

    async getProjectionHandler(): Promise<ProjectionHandlerStatus> {
        const response = await this.request('GET', '/api/projection-handler');
        return decodeHandlerStatus(response.body ?? '');
    }

    async pauseProjectionHandler(): Promise<ProjectionHandlerStatus> {
        const response = await this.request('POST', '/api/projection-handler/pause');
        return decodeHandlerStatus(response.body ?? '');
    }

    async resumeProjectionHandler(): Promise<ProjectionHandlerStatus> {
        const response = await this.request('POST', '/api/projection-handler/resume');
        return decodeHandlerStatus(response.body ?? '');
    }

    send(command: SavedCommand): Promise<HttpResponse<string>> {
        // Повтор отправляет исходные URL, метод, строку JSON и Idempotency-Key буквально без изменений.
        return this.request(command.method, command.url, command.body, command.key);
    }

    private async request(method: string, url: string, body?: string, key?: string): Promise<HttpResponse<string>> {
        try {
            return await firstValueFrom(this.http.request(method, url, {
                body,
                observe: 'response',
                responseType: 'text',
                headers: key ? { 'Content-Type': 'application/json', 'Idempotency-Key': key } : {},
            }).pipe(timeout(20_000)));
        } catch (error: unknown) {
            if (!(error instanceof HttpErrorResponse) || error.status === 0) {
                throw new ApiFailure('Сервер недоступен или время ожидания истекло. Проверьте запуск бэкенда и соединение.',
                    0, null, null);
            }
            const raw = typeof error.error === 'string' ? error.error : null;
            let problem: ProblemDetail = {};
            if (raw) {
                try {
                    const parsed = parseSafeJson(raw);
                    if (isRecord(parsed)) problem = parsed as ProblemDetail;
                } catch (parseError: unknown) {
                    if (parseError instanceof NumericRangeError) {
                        throw new ApiFailure(parseError.message, error.status, null, raw);
                    }
                    // Proxy может вернуть обычный текст вместо ProblemDetail; сохраняем его как есть.
                }
            }
            const code = typeof problem.code === 'string' ? problem.code : null;
            const detail = typeof problem.detail === 'string' ? problem.detail : null;
            throw new ApiFailure((code ? MESSAGES[code] : null) ?? detail
                ?? `Ошибка HTTP ${error.status}. Проверьте доступность сервера.`, error.status, code, raw);
        }
    }
}
