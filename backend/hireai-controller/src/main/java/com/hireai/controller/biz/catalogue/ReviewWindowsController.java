package com.hireai.controller.biz.catalogue;

import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.catalogue.dto.ReviewWindowsDTO;
import com.hireai.domain.biz.task.model.ReviewWindow;
import com.hireai.domain.biz.task.rejection.model.RejectionWindows;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/catalogue/review-windows}: the windows every booking agrees to (#94, #95), so the
 * booking screen states them from configuration instead of restating a number that can drift (#101).
 */
@RestController
@RequestMapping("/api/catalogue")
public class ReviewWindowsController extends BaseController {

    private final ReviewWindow reviewWindow;
    private final RejectionWindows rejectionWindows;

    public ReviewWindowsController(ReviewWindow reviewWindow, RejectionWindows rejectionWindows) {
        this.reviewWindow = reviewWindow;
        this.rejectionWindows = rejectionWindows;
    }

    @GetMapping("/review-windows")
    public WebResult<ReviewWindowsDTO> reviewWindows() {
        return ok(new ReviewWindowsDTO(
                reviewWindow.length().toSeconds(),
                rejectionWindows.agentResponse().toSeconds(),
                rejectionWindows.clientDecision().toSeconds()));
    }
}
