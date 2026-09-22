/** HTTP-контракт существующего WalletController. Числа проходят проверку диапазона до использования. */
export interface WalletState {
    readonly walletId: string;
    readonly balanceMinor: number;
    readonly currency: 'RUB';
    readonly status: 'ACTIVE' | 'CLOSED';
    readonly version: number;
}

export interface CreateWalletRequest { readonly currency: 'RUB'; }
export interface CloseWalletRequest { readonly expectedVersion: number; }
export interface MoneyRequest extends CloseWalletRequest { readonly amountMinor: number; }
export type CommandBody = CreateWalletRequest | CloseWalletRequest | MoneyRequest;
export type CommandKind = 'CreateWallet' | 'DepositMoney' | 'WithdrawMoney' | 'CloseWallet';
export type MoneyOperation = 'deposits' | 'withdrawals';

export interface WalletEvent {
    readonly eventId: string;
    readonly walletId: string;
    readonly streamVersion: number;
    readonly eventType: string;
    readonly schemaVersion: number;
    readonly payload: Readonly<Record<string, unknown>>;
    readonly occurredAt: string;
    readonly commandId: string;
}

export interface EventPage {
    readonly items: readonly WalletEvent[];
    readonly nextAfterVersion: number;
    readonly hasMore: boolean;
}

export interface ProblemDetail {
    readonly type?: string;
    readonly title?: string;
    readonly status?: number;
    readonly detail?: string;
    readonly instance?: string;
    readonly code?: string;
}

/** Тело сохраняется строкой: повтор не пересобирает JSON из изменившейся формы или версии. */
export interface SavedCommand {
    readonly kind: CommandKind;
    readonly walletId: string;
    readonly method: 'PUT' | 'POST';
    readonly url: string;
    readonly key: string;
    readonly expectedVersion: number | null;
    readonly body: string;
}

export interface LastOperation {
    readonly request: SavedCommand;
    readonly status: number | null;
    readonly responseBody: string | null;
    readonly pending: boolean;
    readonly uncertain: boolean;
    readonly repeated: boolean;
    readonly message: string;
}

export interface RecentWallet { readonly id: string; }
/** Модели прочитаны последовательно; проекция сверена с историей на её бизнес-версии. */
export interface WalletComparison {
    readonly eventState: WalletState;
    readonly readModel: WalletState | null;
    readonly streamVersion: number;
    readonly projectionVersion: number;
    readonly pendingEvents: number;
    readonly status: 'MATCHED' | 'LAGGING';
    readonly matches: boolean;
}
export interface ProjectionHandlerStatus {
    readonly status: 'RUNNING' | 'PAUSE_REQUESTED' | 'PAUSED' | 'IDLE';
    readonly paused: boolean;
    readonly pauseRequested: boolean;
    readonly processing: boolean;
    readonly lastError: string | null;
}
export interface MoneyIntent { readonly operation: MoneyOperation; readonly amountMinor: number; }
