package com.hireai.domain.biz.task.sampling.repository;

import com.hireai.domain.biz.task.sampling.model.InspectionModel;
import com.hireai.domain.biz.task.sampling.model.ItemJudgementModel;
import com.hireai.domain.biz.task.sampling.model.SampleDrawModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A multi-Item Task's sampling inspection (spec S8): its state, its drawn samples and the reviewer's
 * judgements. Callers hold the Task row lock, which serializes everything about one Task.
 */
public interface InspectionRepository {

    /** Inserts or updates the inspection's state. */
    void save(InspectionModel inspection);

    Optional<InspectionModel> findByTaskId(UUID taskId);

    /** Records a new draw (its seed and Items never change afterwards). */
    void insertDraw(SampleDrawModel draw);

    /** Records a draw's decision, once. */
    void recordDecision(SampleDrawModel decided);

    /** Every draw of the Task, by round then stage. */
    List<SampleDrawModel> findDraws(UUID taskId);

    /** Appends one reviewer judgement (append-only). */
    void appendJudgement(ItemJudgementModel judgement);

    /** Every judgement of the Task, oldest first. */
    List<ItemJudgementModel> findJudgements(UUID taskId);
}
