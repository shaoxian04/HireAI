package com.hireai.controller.biz.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hireai.application.biz.task.sampling.SamplingAppService.InspectionView;
import com.hireai.domain.biz.task.sampling.model.ItemJudgementModel;
import com.hireai.domain.biz.task.sampling.model.ItemRejectionModel;
import com.hireai.domain.biz.task.sampling.model.SampleDrawModel;
import com.hireai.domain.biz.task.sampling.model.SampleSizeTable;
import com.hireai.domain.biz.task.sampling.model.SamplingPlan;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A multi-Item Task's sampling inspection (spec S8) for its buyer: the level, the round (1, or 2 after
 * the whole-Task rework), how it stands, every sample drawn (its plan, seed and Items: the seed is a
 * string, since it is a 64-bit number), the reviewer's judgements, and each rejected Item's response path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InspectionDTO(
        String level,
        int round,
        /** SAMPLING, REWORKING, ACCEPTED, PRO_RATA or WAIVED. */
        String status,
        BigDecimal errorRate,
        BigDecimal aql,
        List<DrawDTO> draws,
        List<JudgementDTO> judgements,
        List<ItemRejectionDTO> itemRejections
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DrawDTO(int round, int stage, String seed, String codeLetter, int lotSize,
                          int firstSampleSize, int secondSampleSize, int firstAccept, int firstReject,
                          int secondAccept, int secondReject, boolean fullInspection, List<Integer> itemNos,
                          Instant drawnAt, Integer defects, String decision, Instant decidedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JudgementDTO(int itemNo, int round, boolean passed, String reasonCategory, String reason,
                               boolean sampled, Instant judgedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemRejectionDTO(int itemNo, RejectionDTO rejection) {
    }

    public static InspectionDTO from(InspectionView view) {
        return new InspectionDTO(view.inspection().level().name(), view.inspection().round(),
                view.inspection().status().name(), view.inspection().errorRate(), SampleSizeTable.AQL,
                view.draws().stream().map(InspectionDTO::draw).toList(),
                view.judgements().stream().map(InspectionDTO::judgement).toList(),
                view.rejections().stream().map(InspectionDTO::rejection).toList());
    }

    private static DrawDTO draw(SampleDrawModel d) {
        SamplingPlan p = d.plan();
        return new DrawDTO(d.round(), d.stage(), Long.toString(d.seed()), String.valueOf(p.codeLetter()),
                p.lotSize(), p.firstSampleSize(), p.secondSampleSize(), p.firstAccept(), p.firstReject(),
                p.secondAccept(), p.secondReject(), p.fullInspection(), d.itemNos(), d.drawnAt(), d.defects(),
                d.decision() == null ? null : d.decision().name(), d.decidedAt());
    }

    private static JudgementDTO judgement(ItemJudgementModel j) {
        return new JudgementDTO(j.itemNo(), j.round(), j.passed(),
                j.reasonCategory() == null ? null : j.reasonCategory().name(), j.reason(), j.sampled(), j.judgedAt());
    }

    private static ItemRejectionDTO rejection(ItemRejectionModel r) {
        return new ItemRejectionDTO(r.itemNo(), RejectionDTO.from(r.round()));
    }
}
