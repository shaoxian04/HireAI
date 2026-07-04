// ── Backend enum mirrors (exact spellings — see OutputFormat/TaskStatus/AgentStatus .java) ──

/** Declared deliverable shape (task/enums/OutputFormat.java). */
export type OutputFormat = "TEXT" | "JSON" | "FILE";

/** Client review outcome (task/enums/TaskResolution.java). */
export type TaskResolution = "ACCEPTED" | "REJECTED" | "PARTIALLY_ACCEPTED";

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
  | "CANCELLED"
  | "DISPUTED";

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

export interface RegisterRequest {
  email: string;
  password: string;
  displayName?: string;
}

export interface LoginResponse {
  token: string;
  userId: string;
  roles: Role[];
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
  resolution?: TaskResolution | null;
  resolvedAt?: string | null;
  rejectionReason?: string | null;
  payoutAmount?: number | null;
  commissionAmount?: number | null;
  refundAmount?: number | null;
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

// ── Catalogue (public discovery) ──

export interface AgentCardDTO {
  id: string;
  name: string;
  builderName: string;
  tagline: string | null;
  logoUrl: string | null;
  coverUrl: string | null;
  categories: string[];
  price: number;
  maxExecutionSeconds: number;
  reputationScore: number;
  ratingAvg: number | null;
  ratingCount: number;
  requestCount: number;
  featured: boolean;
  createdAt: string;
}

export interface CatalogueReviewDTO {
  id: string;
  rating: number;
  reviewText: string | null;
  builderResponse: string | null;
  author: string;
  createdAt: string;
}

export interface CatalogueStatsDTO {
  requestCount: number;
  completedCount: number;
  successRate: number | null;
  avgTurnaroundSeconds: number | null;
}

export interface AgentProfileDTO {
  card: AgentCardDTO;
  description: string | null;
  sampleOutput: string | null;
  galleryUrls: string[];
  outputSpec: OutputSpecDTO;
  stats: CatalogueStatsDTO;
  reviews: CatalogueReviewDTO[];
}

export interface CategoryCountDTO {
  category: string;
  agentCount: number;
}

// ── Direct booking ──

export interface DirectBookRequest {
  title: string;
  description: string;
  budget: number;
  agentId: string;
}

export type CatalogueSort = "hot" | "rating" | "price_asc" | "price_desc" | "newest";

// ── Builder storefront management ──

export interface AgentProfileViewDTO {
  tagline: string | null;
  description: string | null;
  sampleOutput: string | null;
  logoUrl: string | null;
  coverUrl: string | null;
  galleryUrls: string[];
  listed: boolean;
  featured: boolean;
}

export interface UpdateProfileRequest {
  tagline: string | null;
  description: string | null;
  sampleOutput: string | null;
  isListed: boolean;
}

export interface PublishVersionRequest {
  price: number;
  maxExecutionSeconds: number;
  capabilityCategories: string[];
}

export interface BuilderReviewDTO {
  id: string;
  rating: number;
  reviewText: string | null;
  builderResponse: string | null;
  createdAt: string;
}

export interface AgentStatsDTO {
  volume: { total: number; completed: number; failed: number; open: number; successRate: number | null };
  performance: { avgTurnaroundSeconds: number | null; onTimeRate: number | null };
  earnings: { creditsInEscrow: number; potentialEarnings: number };
  trend: { day: string; count: number }[];
  recentTasks: { id: string; title: string; status: TaskStatus; createdAt: string }[];
}

export type MediaKind = "logo" | "cover" | "gallery";

// ── Builder earnings ──────────────────────────────────────────────────────

export interface AgentEarningsDTO {
  agentId: string;
  agentName: string;
  earned: number;
  pendingIfAccepted: number;
  paidTaskCount: number;
}

export interface PayoutDTO {
  taskId: string;
  taskTitle: string;
  agentName: string;
  amount: number;
  settledAt: string;
}

export interface BuilderEarningsDTO {
  lifetimeEarned: number;
  pendingIfAccepted: number;
  paidTaskCount: number;
  perAgent: AgentEarningsDTO[];
  payouts: PayoutDTO[];
}

// ── Dispute / arbitration ──────────────────────────────────────────────────

/**
 * Dispute reason categories exposed in the reject UI (A/B/C open a dispute;
 * D_CHANGED_MIND is a backend-only option that charges the client in full — NOT
 * offered through this UI).
 */
export type RejectReason = "A_MISMATCH" | "B_FACTUAL" | "C_INCOMPLETE";

export type RulingCategory = "FULFILLED" | "PARTIALLY_FULFILLED" | "NOT_FULFILLED";
export type RulingDecidedBy = "ARBITRATOR" | "ADMINISTRATOR" | "FALLBACK";

export interface RulingDTO {
  tier: number;
  decidedBy: RulingDecidedBy;
  category: RulingCategory;
  rationale: string | null;
  decidedAt: string; // ISO-8601
}

export interface DisputeOutcomeDTO {
  disputeId: string;
  taskId: string;
  status: string;
  reasonCategory: RejectReason | null;
  effectiveCategory: RulingCategory | null;
  rulings: RulingDTO[];
}

/** Row shape for a client's own dispute list (future "my disputes" view). */
export interface DisputeMineRowDTO {
  disputeId: string;
  taskId: string;
  taskTitle: string;
  status: string; // OPEN | ARBITRATING | RULED | ESCALATED | RESOLVED
  proposedCategory: RulingCategory | null;
  updatedAt: string;
}

// ── Admin surface ───────────────────────────────────────────────────────────

export interface AdminOverviewDTO {
  disputesOpen: number;
  disputesArbitrating: number;
  disputesEscalated: number;
  disputesResolved: number;
  tasksTotal: number;
  usersTotal: number;
  agentsTotal: number;
  escrowHeld: number;
  commissionEarned: number;
}

export interface AdminDisputeRowDTO {
  disputeId: string;
  taskId: string;
  taskTitle: string;
  status: string;
  reasonCategory: RejectReason;
  createdAt: string;
  clientName: string;
  hasArbitratorRuling: boolean;
  needsAttention: boolean;
}

export interface AdminSettlementPreviewDTO {
  budget: number;
  fulfilledPayout: number;
  fulfilledCommission: number;
  notFulfilledRefund: number;
  partialBuilderNet: number;
  partialClientRefund: number;
}

export interface AdminDisputeDetailDTO {
  disputeId: string;
  taskId: string;
  taskTitle: string;
  taskDescription: string;
  status: string;
  reasonCategory: RejectReason;
  clientReason: string | null;
  createdAt: string;
  clientName: string;
  budget: number;
  category: string | null;
  outputFormat: string | null;
  submittedAt: string | null;
  resultReceivedAt: string | null;
  agentName: string | null;
  builderName: string | null;
  agentReputation: number | null;
  agentPrice: number | null;
  outputSpecJson: string | null;
  resultPayloadJson: string | null;
  resultUrl: string | null;
  agentStatus: string | null;
  actionable: boolean;
  settlementPreview: AdminSettlementPreviewDTO | null;
  rulings: RulingDTO[];
}

export interface AdminTaskRowDTO {
  id: string;
  title: string;
  status: TaskStatus;
  budget: number;
  clientName: string;
  createdAt: string;
}

export interface AdminUserRowDTO {
  id: string;
  name: string;
  email: string;
  roles: Role[];
  availableBalance: number;
  escrowBalance: number;
}

export interface AdminAgentRowDTO {
  id: string;
  name: string;
  status: string;
  builderName: string;
  reputationScore: number;
  price: number | null;
}
