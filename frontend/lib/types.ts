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
  | "AWAITING_AGENT_RESPONSE"
  | "AWAITING_CLIENT_DECISION"
  | "RESOLVED"
  | "AWAITING_AGENT"
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

// ── Acceptance: Template → Offer → Contract (ADR 0009) ──

/** The one comparison a Template parameter follows. */
export type ParameterComparison = "max" | "min" | "subset" | "enum" | "flag";

/** `GET /catalogue/templates` — a published, versioned Template. */
export interface TemplateDTO {
  key: string;
  version: number;
  title: string;
  description: string | null;
  outputShape: OutputFormat;
  checks: string[];
  parameters: TemplateParameterDTO[];
  expectationMode: string;
  /** Fraction of the price the Builder's side receives under each ruling. */
  rulingSettlement: Record<string, number>;
  status: "ACTIVE" | "RETIRED";
  publishedAt: string;
}

export interface TemplateParameterDTO {
  name: string;
  label: string;
  description: string | null;
  comparison: ParameterComparison;
  /** Only the fields the comparison uses are present. */
  bounds: { minimum?: number; maximum?: number; allowedValues?: string[]; maxLength?: number };
}

/** A contract parameter value: number (max/min), string (enum), string[] (subset), boolean (flag). */
export type ContractValue = number | string | string[] | boolean;

/** The Contract a Task froze at submit. */
export interface ContractDTO {
  template: string;
  version: number;
  params: Record<string, ContractValue>;
}

/** The range an Offer accepts for one parameter. */
export type OfferRange = { bound: number } | { oneOf: string[] } | { any: true } | { acceptsTrue: boolean };

/** An agent version's Offer: its price and the Templates and ranges it accepts. */
export interface OfferDTO {
  price: number;
  terms: { template: string; version: number; params: Record<string, OfferRange> }[];
}

// ── Agents ──

export interface AgentVersionDTO {
  outputSpec: OutputSpecDTO;
  /** The version's Offer (absent only in old fixtures). */
  offer?: OfferDTO;
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
  /** Whether an AgentSigningSecret is set. The secret itself is never on a read. */
  hasSigningSecret: boolean;
  /** Whether the storefront is listed. An unlisted agent is never matched, so it receives no work. */
  listed: boolean;
}

/** `POST /agents` response: the agent plus its signing secret, shown this once. */
export interface RegisteredAgentDTO extends AgentDTO {
  signingSecret: string;
}

/** `POST /agents/{id}/rotate-secret` response: the new signing secret, shown this once. */
export interface SigningSecretDTO {
  agentId: string;
  signingSecret: string;
}

export interface CreateAgentRequest {
  name: string;
  outputSpec: OutputSpecDTO;
  capabilityCategories: string[];
  webhookUrl: string;
  maxExecutionSeconds: number;
  price: number;
  maxConcurrent: number;
  /** Optional "suggest a category" text, stored for admins; never used for matching. */
  suggestedCategory?: string;
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
  /** The Contract frozen at submit; absent on tasks submitted before Contracts existed. */
  contract?: ContractDTO | null;
  createdAt: string;
  resolution?: TaskResolution | null;
  resolvedAt?: string | null;
  rejectionReason?: string | null;
  payoutAmount?: number | null;
  commissionAmount?: number | null;
  refundAmount?: number | null;
  /** Why the task ended without a delivered result; absent on live and successful tasks. */
  failureReason?: FailureReason | null;
  /** The capability category the task was matched on. */
  category?: string;
  /** The Agent the task was booked with (`GET /tasks/{id}` only; absent when none was). */
  agentId?: string | null;
  /**
   * When the client's review window closes (`GET /tasks/{id}` only): set once the result awaits
   * review, kept after it resolves. Past it, the platform accepts on the client's behalf (#94).
   */
  reviewDeadline?: string | null;
  /** True when the window closed unreviewed and the result was accepted automatically. */
  autoAccepted?: boolean | null;
  /**
   * The latest round of the rejection response path (`GET /tasks/{id}` only), once the client
   * rejected for reason A/B/C (#95); kept after it settles.
   */
  rejection?: RejectionDTO | null;
}

/** What the agent did with an A/B/C rejection (task/rejection/enums/AgentResponse.java). */
export type AgentRejectionResponse = "REVISE" | "CONCEDE" | "STAND_BY";

/** One round of the rejection response path (#95, spec D20). */
export interface RejectionDTO {
  round: number;
  reasonCategory: RejectReason;
  reason?: string | null;
  rejectedAt: string;
  /** Until when the agent may revise (if `revisionAllowed`), concede or stand by. */
  responseDeadline: string;
  /** False once the agent has revised this task: it may only concede or stand by. */
  revisionAllowed: boolean;
  response?: AgentRejectionResponse | null;
  /** The agent's reason for standing by its result; absent when it stayed silent. */
  responseReason?: string | null;
  /** True when the agent's window closed without a response (counts as standing by). */
  responseSilent: boolean;
  respondedAt?: string | null;
  /** After a stand-by: until when the client may accept or request arbitration. */
  decisionDeadline?: string | null;
  decision?: "ACCEPT" | "ARBITRATE" | null;
  /** True when the client's window closed without a decision (counts as acceptance). */
  decisionSilent: boolean;
  decidedAt?: string | null;
}

/** Mirrors the backend FailureReason enum (set once when a task ends without a result). */
export type FailureReason =
  | "AGENT_UNREACHABLE"
  | "AGENT_BUSY"
  | "AGENT_UNAVAILABLE"
  | "NO_ALTERNATIVE_AGENT"
  | "NO_ELIGIBLE_AGENT"
  | "DISPATCH_ABANDONED"
  | "EXECUTION_TIMEOUT"
  | "AGENT_REPORTED_FAILURE"
  | "SPEC_VIOLATION"
  | "CONTRACT_DECLINED";

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
  /** Where a FILE result was fetched from. Never followed again once a `deliverable` exists. */
  resultUrl: string | null;
  receivedAt: string;
  /** The frozen copy of a FILE result (DeliverySnapshot, #97); absent when none was kept. */
  deliverable?: DeliverableDTO;
}

/** A DeliverySnapshot's metadata; the bytes come from `GET /tasks/{id}/deliverable`. */
export interface DeliverableDTO {
  /** What the platform judged the bytes to be, e.g. `image/png`. */
  mediaType: string;
  /** The agent's Content-Type, when it sent one. */
  declaredMediaType: string | null;
  sizeBytes: number;
  sha256: string;
  fetchedAt: string;
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
  /** Summed outcome quality and sample count — the plain-language delivery record (#42). */
  reliabilitySum: number;
  reliabilityCount: number;
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
  /** Public contract-decline rate (#93). */
  contractDeclines?: ContractDeclineRateDTO;
}

export interface AgentProfileDTO {
  card: AgentCardDTO;
  description: string | null;
  sampleOutput: string | null;
  galleryUrls: string[];
  outputSpec: OutputSpecDTO;
  /** The terms the Agent works under; a booking freezes the matching Contract. */
  offer?: OfferDTO;
  stats: CatalogueStatsDTO;
  reviews: CatalogueReviewDTO[];
}

/** One bucket of the platform-owned category list; `category` is the slug every category field takes. */
export interface CategoryCountDTO {
  category: string;
  label: string;
  agentCount: number;
}

// ── Direct booking ──

export interface DirectBookRequest {
  title: string;
  description: string;
  budget: number;
  agentId: string;
  /** Optional slug the agent serves; defaults to the agent's first category. */
  category?: string;
  /** Optional: seconds from booking the result is needed by; a slower agent is refused. */
  deadlineSeconds?: number;
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
 * Reject reason categories exposed in the reject UI (A/B/C start the rejection response path, #95;
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

/** The two sides of a dispute (#96). */
export type DisputeSide = "CLIENT" | "BUILDER";

/** How one dispute fee was borne: fullCost = clientCharged + builderCharged + platformCovered. */
export interface DisputeFeeDTO {
  type: "ARBITRATION_COST" | "APPEAL_FEE";
  fullCost: number;
  cappedAmount: number;
  clientCharged: number;
  builderCharged: number;
  platformCovered: number;
}

export interface DisputeOutcomeDTO {
  disputeId: string;
  taskId: string;
  status: string;
  reasonCategory: RejectReason | null;
  effectiveCategory: RulingCategory | null;
  rulings: RulingDTO[];
  taskTitle?: string;
  /** The task's price, in credits (the fee caps are 20% of it). */
  price?: number;
  /** The viewer's side(s): CLIENT, BUILDER, or both on a self-booked task. */
  yourSides?: DisputeSide[];
  /** Sides the proposed ruling goes against that may still accept or appeal it. */
  awaiting?: DisputeSide[];
  appealedBy?: DisputeSide | null;
  /** While RULED: when the proposed ruling is accepted on everyone's behalf. */
  appealDeadline?: string | null;
  /** Capped amounts the loser bears (credits). */
  arbitrationCost?: number;
  appealFee?: number;
  /** How each fee was borne, once settled. */
  fees?: DisputeFeeDTO[];
  clientReason?: string | null;
  agentResponse?: "REVISE" | "CONCEDE" | "STAND_BY" | null;
  agentReason?: string | null;
  agentSilent?: boolean;
}

/** Row shape for a user's own dispute list (/client/disputes, /builder/disputes). */
export interface DisputeMineRowDTO {
  disputeId: string;
  taskId: string;
  taskTitle: string;
  status: string; // OPEN | ARBITRATING | RULED | ESCALATED | RESOLVED
  proposedCategory: RulingCategory | null;
  updatedAt: string;
  /** The proposed ruling goes against the viewer's side, which has not accepted or appealed it yet. */
  awaitingYou?: boolean;
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
  /** The disputed result's frozen file (#97); null when none was kept. */
  deliverable: DeliverableDTO | null;
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

// ── Matching / Shortlist ────────────────────────────────────────────────────

export interface AgentOptionDTO {
  agentId: string;
  agentVersionId: string;
  agentName: string;
  tagline: string | null;
  logoUrl: string | null;
  price: number;
  /** Platform-witnessed delivery record — rendered instead of the routing composite (#42). */
  reliabilitySum: number;
  reliabilityCount: number;
  reputationScore: number;
  availability: "AVAILABLE" | "BUSY";
  outputFormat: string | null;
  capabilityCategories: string[];
  /** Public contract-decline rate (#93). */
  contractDeclines?: ContractDeclineRateDTO;
}

/**
 * An agent's public contract-decline rate: `declined` of the `answered` work orders whose terms it
 * took or turned down (busy or unreachable replies don't count). `rate` is null when it answered none.
 */
export interface ContractDeclineRateDTO {
  declined: number;
  answered: number;
  rate: number | null;
}

/** Mirrors the backend DispatchOutcome enum. */
export type DispatchOutcome = "ACCEPTED" | "UNREACHABLE" | "BUSY" | "UNAVAILABLE" | "CONTRACT_DECLINED";

/** One row of `GET /tasks/{id}/attempts`: which agent the task was handed to, and how that ended. */
export interface DispatchAttemptDTO {
  attemptNumber: number;
  agentId: string | null;
  agentName: string | null;
  agentVersionId: string;
  outcome: DispatchOutcome;
  occurredAt: string;
  /** The agent's own reason on a CONTRACT_DECLINED row. */
  declineReason: string | null;
}

/**
 * Body of `POST /tasks/match-preview`. The task text travels in the body, never in a URL.
 * `deadlineSeconds` is accepted by the backend but does not filter the shortlist yet (#83).
 */
export interface MatchPreviewRequest {
  title: string;
  description: string;
  category: string;
  budget: number;
  /** Optional: seconds from now the result is needed by; slower agents are left out. */
  deadlineSeconds?: number;
  /** Agents to leave out of both lists (the Agent that failed a rebooked task). */
  exclude?: string[];
}

export interface MatchPreviewDTO {
  shortlist: AgentOptionDTO[];
  nearMisses: AgentOptionDTO[];
  /** True when the shortlist is empty only because no in-budget agent's profile fits the task text. */
  noCloseMatch?: boolean;
}

// ── Validation report (spec-violation reason) ───────────────────────────────

/** PASS, a deterministic REJECT (reworked, then refunded), or REVIEW: the check could not decide (#98). */
export type CheckOutcome = "PASS" | "REJECT" | "REVIEW";

export interface ValidationCheckDTO {
  rule: string;
  passed: boolean;
  detail: string | null;
  outcome?: CheckOutcome;
}

/** A fact about the brief noticed while accepting (spec D7): shown to the client, never moves money. */
export interface FindingDTO {
  code: string;
  detail: string | null;
}

/** Something the agent itself reported with its result; it sends the result to the client's review. */
export interface AnomalyDTO {
  check: string | null;
  description: string;
}

export interface ValidationAttemptDTO {
  attemptNo: number;
  verdict: "PASS" | "FAIL" | "REVIEW";
  failedChecks: ValidationCheckDTO[];
}

export interface ValidationReportDTO {
  /** FAIL: a check rejected it; REVIEW: none did, but one could not decide or the agent flagged an anomaly. */
  verdict: "PASS" | "FAIL" | "REVIEW";
  checks: ValidationCheckDTO[];
  attemptNo?: number;
  findings?: FindingDTO[];
  anomalies?: AnomalyDTO[];
  /** How many of the task's results a check rejected (each but the last was sent back for rework). */
  rejectedAttempts?: number;
  maxReworks?: number;
  previousAttempts?: ValidationAttemptDTO[];
}

// ── API keys (programmatic submission) ──────────────────────────────────────

export interface ApiKeyDTO {
  id: string;
  name: string;
  displayPrefix: string;
  spendCap: number | null;
  dailySpendCap: number | null;
  status: "ACTIVE" | "REVOKED";
  lastUsedAt: string | null;
  createdAt: string;
}

export interface CreateApiKeyRequest {
  name: string;
  spendCap?: number | null;
  dailySpendCap?: number | null;
}

/** Returned ONLY from POST /api/keys — the one place `rawKey` ever appears. */
export interface CreatedApiKeyDTO {
  id: string;
  name: string;
  displayPrefix: string;
  spendCap: number | null;
  dailySpendCap: number | null;
  rawKey: string;
}

// ── Push webhooks (per-API-key subscription + delivery log) ────────────────

/** Mirrors the backend's SubscriptionDTO record (WebhookSubscriptionController). */
export type WebhookSubscriptionDTO = {
  id: string;
  apiKeyId: string;
  callbackUrl: string;
  signingSecret: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
};

/** Mirrors the backend's DeliveryDTO record (WebhookDeliveryController). */
export type WebhookDeliveryDTO = {
  eventId: string;
  taskId: string;
  eventType: "task.completed" | "task.failed";
  status: "PENDING" | "DELIVERED" | "DEAD";
  attempts: number;
  nextAttemptAt: string;
  createdAt: string;
  deliveredAt: string | null;
  lastError: string | null;
};

/**
 * The builder-facing reputation breakdown (GET /api/agents/{id}/reputation).
 *
 * Reliability and Satisfaction stay separate deliberately: a single blended number cannot tell a
 * builder whether the agent is failing to deliver or delivering work clients dislike. `unproven`
 * is sent explicitly so the UI never has to re-derive that an agent with no evidence reads as
 * unproven rather than excellent.
 */
export type ReputationComponentDTO = {
  /** On the same 0–100 scale as the headline score. */
  value: number;
  /** The confidence behind it — 90 over 40 tasks is a far stronger claim than over three. */
  sampleCount: number;
  /**
   * The share of `value` that is still the neutral starting point rather than witnessed
   * performance: 1 at zero samples, decaying as evidence lands. Comes from the server so the UI
   * never has to restate kR/kS — a duplicated threshold here would quietly start lying the moment
   * the policy was tuned.
   */
  priorWeight: number;
  /** This component's share of the blended score (α for Reliability, 1−α for Satisfaction). */
  weight: number;
};

export type ReputationEventDTO = {
  id: string;
  taskId: string | null;
  eventType:
    | "TASK_ACCEPTED"
    | "SPEC_VIOLATION"
    | "EXECUTION_FAILED"
    | "EXECUTION_TIMEOUT"
    | "DISPUTE_WON"
    | "DISPUTE_PARTIAL"
    | "DISPUTE_LOST"
    | "REJECTION_CONCEDED"
    | "RATING";
  quality: number;
  weight: number;
  occurredAt: string;
};

export type AgentReputationDTO = {
  score: number;
  reliability: ReputationComponentDTO;
  satisfaction: ReputationComponentDTO;
  unproven: boolean;
  recentEvents: ReputationEventDTO[];
};
