package com.hireai.domain.biz.acceptance.check.model;

import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;

import java.util.List;

/**
 * Everything the acceptance engine judges a result on besides the result itself, gathered by the
 * application layer before the checks run:
 * <ul>
 *   <li>{@code delivery}: the DeliverySnapshot or why there is none (D24); null when not a file;</li>
 *   <li>{@code image}: the snapshot's pixels, or null when it is not a decodable image;</li>
 *   <li>{@code anomalies}: what the Agent self-reported with the result (D7);</li>
 *   <li>{@code facts}: outside data, read only by Finding checks;</li>
 *   <li>{@code invoice}: an {@code ap_invoice_extraction} result as the {@code InvoiceReader} read it;
 *       null for every other Template.</li>
 * </ul>
 */
public record AcceptanceEvidence(DeliveryOutcome delivery, DecodedImage image,
                                 List<SelfReportedAnomaly> anomalies, FindingFacts facts, InvoiceReading invoice) {

    public AcceptanceEvidence {
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
        facts = facts == null ? FindingFacts.NONE : facts;
    }

    public AcceptanceEvidence(DeliveryOutcome delivery, DecodedImage image, List<SelfReportedAnomaly> anomalies,
                              FindingFacts facts) {
        this(delivery, image, anomalies, facts, null);
    }

    public static AcceptanceEvidence ofDelivery(DeliveryOutcome delivery) {
        return new AcceptanceEvidence(delivery, null, List.of(), FindingFacts.NONE);
    }
}
