// ── Backend enum mirrors (exact spellings — see OutputFormat/TaskStatus/AgentStatus .java) ──

/** Declared deliverable shape (task/enums/OutputFormat.java). */
export type OutputFormat = "TEXT" | "JSON" | "FILE";

/** Full task lifecycle (task/enums/TaskStatus.java). */
export type TaskStatus =
  | "SUBMITTED"
  | "QUEUED"
  | "EXECUTING"
  | "RESULT_RECEIVED"
  | "PENDING_REVIEW"
  | "RESOLVED"
  | "AWAITING_CAPACITY"
  | "TIMED_OUT"
  | "SPEC_VIOLATION"
  | "FAILED"
  | "CANCELLED";

/** Agent lifecycle (agent/enums/AgentStatus.java). */
export type AgentStatus =
  | "PENDING_VERIFICATION"
  | "ACTIVE"
  | "SUSPENDED"
  | "DEACTIVATED";

export type Role = "CLIENT" | "BUILDER" | "ADMIN";

// ── Response envelope ──

/** Every backend response is wrapped in this. `data` is null on error. */
export interface WebResult<T> {
  success: boolean;
  code: string;
  message: string;
  data: T | null;
}

// ── Auth ──

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  userId: string;
  role: Role;
}

// ── Wallet ──

export interface WalletDTO {
  walletId: string;
  availableBalance: number;
  escrowBalance: number;
}

export interface TopupRequest {
  amount: number;
}

// ── Output spec (shared by agent registration & task submission) ──

export interface OutputSpecDTO {
  format: OutputFormat;
  schema: string;
  acceptanceCriteria: string;
}

// ── Agents ──

export interface AgentVersionDTO {
  outputSpec: OutputSpecDTO;
  capabilityCategories: string[];
  webhookUrl: string;
  maxExecutionSeconds: number;
  price: number;
}

export interface AgentDTO {
  id: string;
  ownerId: string;
  name: string;
  status: AgentStatus;
  currentVersionId: string;
  reputationScore: number;
  currentVersion: AgentVersionDTO;
  createdAt: string;
}

export interface CreateAgentRequest {
  name: string;
  outputSpec: OutputSpecDTO;
  capabilityCategories: string[];
  webhookUrl: string;
  maxExecutionSeconds: number;
  price: number;
}

// ── Tasks ──

export interface TaskDTO {
  id: string;
  clientId: string;
  title: string;
  description: string;
  budget: number;
  status: TaskStatus;
  outputSpec: OutputSpecDTO;
  createdAt: string;
}

export interface CreateTaskRequest {
  title: string;
  description: string;
  category: string;
  budget: number;
  outputSpec: OutputSpecDTO;
}

export interface TaskResultDTO {
  taskId: string;
  agentStatus: string;
  resultPayloadJson: string;
  resultUrl: string | null;
  receivedAt: string;
}
