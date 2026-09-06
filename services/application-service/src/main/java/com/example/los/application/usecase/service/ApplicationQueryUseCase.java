package com.example.los.application.usecase.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.application.domain.exception.ApplicationNotFoundException;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.ApplicationStatus;
import com.example.los.application.domain.model.LoanApplication;
import com.example.los.application.usecase.port.LoanApplicationRepository;

/**
 * Read-only access to applications.
 *
 * <p>Separated from the command use cases so the read path can be given
 * {@code readOnly} transactions, and so it becomes obvious at a glance which
 * operations can change state. If the read load ever justified it, this is the
 * seam at which a dedicated read model or a replica data source would be
 * introduced without touching the write path.
 */
@Service
public class ApplicationQueryUseCase {

    /** Bounded so a client cannot ask for an unbounded page and exhaust memory. */
    public static final int MAX_PAGE_SIZE = 100;

    private final LoanApplicationRepository applications;

    public ApplicationQueryUseCase(LoanApplicationRepository applications) {
        this.applications = applications;
    }

    @Transactional(readOnly = true)
    public LoanApplication getById(ApplicationId applicationId) {
        return applications
                .findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    /** One page of applications in a status, oldest first. Backs the manual-review queue. */
    @Transactional(readOnly = true)
    public Page findByStatus(ApplicationStatus status, int page, int size) {
        int effectiveSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 0);

        List<LoanApplication> items =
                applications.findByStatus(status, effectivePage * effectiveSize, effectiveSize);
        long total = applications.countByStatus(status);

        return new Page(items, effectivePage, effectiveSize, total);
    }

    /**
     * One page of results.
     *
     * @param items      the applications on this page
     * @param page       zero-based page number
     * @param size       page size actually used, after clamping
     * @param totalItems total number of matching applications
     */
    public record Page(List<LoanApplication> items, int page, int size, long totalItems) {

        public Page {
            items = List.copyOf(items);
        }

        public int totalPages() {
            return size == 0 ? 0 : (int) Math.ceilDiv(totalItems, size);
        }

        public boolean hasNext() {
            return (long) (page + 1) * size < totalItems;
        }
    }
}
