export const MAX_SAFE_VALUE = Number.MAX_SAFE_INTEGER;
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class NumericRangeError extends Error {
    constructor() {
        super('Число в ответе выходит за безопасный диапазон UI (до 9 007 199 254 740 991). '
            + 'Точное состояние не отображается; команды по этим данным запрещены.');
    }
}

/** Рубли разбираются как текст и BigInt: умножения дробного number на 100 и округления нет. */
export function parseRubles(value: string): number {
    const match = /^(\d{1,14})(?:[.,](\d{1,2}))?$/.exec(value.trim());
    if (!match?.[1]) {
        throw new Error('Введите сумму в рублях, например 1500,50. Допустимо не более двух знаков после запятой.');
    }
    const minor = BigInt(match[1]) * 100n + BigInt((match[2] ?? '').padEnd(2, '0'));
    if (minor <= 0n) throw new Error('Сумма должна быть больше нуля.');
    if (minor > BigInt(MAX_SAFE_VALUE)) throw new Error('Максимальная сумма UI — 90 071 992 547 409,91 ₽.');
    return Number(minor);
}

/** Форматирование целых копеек также не проходит через дробное число рублей. */
export function formatMoney(minor: number): string {
    if (!Number.isSafeInteger(minor) || minor < 0) return 'Вне безопасного диапазона UI';
    const amount = BigInt(minor);
    return `${(amount / 100n).toLocaleString('ru-RU')},${(amount % 100n).toString().padStart(2, '0')} ₽`;
}

export function parseVersion(value: string): number {
    if (!/^[1-9]\d{0,15}$/.test(value.trim())) throw new Error('Введите целую версию от 1 до 9 007 199 254 740 991.');
    const version = BigInt(value.trim());
    if (version > BigInt(MAX_SAFE_VALUE)) throw new NumericRangeError();
    return Number(version);
}

/**
 * API возвращает long как JSON number. Любое небезопасное целое отвергается до попадания
 * в signals и команды. Исходный HTTP-текст отдельно сохраняется для панели операции.
 * Все числовые поля этого API целые; дробные ответы также считаются неподдерживаемыми.
 */
export function parseSafeJson(raw: string): unknown {
    return JSON.parse(raw, (_key: string, value: unknown): unknown => {
        if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new NumericRangeError();
        return value;
    }) as unknown;
}

export function displayJson(raw: string): string {
    try {
        return JSON.stringify(parseSafeJson(raw), null, 2);
    } catch {
        // Нельзя форматировать слишком большой long через JSON.parse/stringify: он уже округлён.
        return raw;
    }
}
