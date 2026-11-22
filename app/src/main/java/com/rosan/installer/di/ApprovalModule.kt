// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.di

import com.rosan.installer.data.engine.approval.InMemoryApprovalStateStore
import com.rosan.installer.data.engine.approval.SessionBindingFactsProvider
import com.rosan.installer.data.engine.install.SessionInstallationGateway
import com.rosan.installer.domain.engine.approval.ApprovalCoordinator
import com.rosan.installer.domain.engine.approval.ApprovalStateStore
import com.rosan.installer.domain.engine.approval.BindingFactsProvider
import com.rosan.installer.domain.engine.approval.SubmissionLedger
import com.rosan.installer.domain.engine.install.InstallationGateway
import com.rosan.installer.domain.session.repository.InstallerSessionRepository
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * Implements: ADR-002.6, ADR-002.7, ADR-002.3 (contract)
 * Enforces: REQ-IX-ARCH-001, REQ-IX-INV-003, REQ-IX-INV-005
 *
 * Koin wiring for the approval chain, following the existing one-file-per-
 * module pattern (`structural-constraints.md`).
 *
 * **Shared (single):** [ApprovalStateStore] (in-memory; not durable, DEBT-003)
 * and [SubmissionLedger] (dedup per operation ID across the whole app,
 * INV-005). **Per session (factory):** [BindingFactsProvider],
 * [InstallationGateway], and [ApprovalCoordinator] — each bound to one
 * `InstallerSessionRepository` so two concurrent sessions cannot cross-
 * contaminate facts or bindings (rev.3 §2.9.1).
 *
 * The per-session coordinator/gateway are not injectable into UI/AI/policy;
 * only the session wiring (checkpoint C) resolves them.
 */
val approvalModule = module {
    singleOf(::InMemoryApprovalStateStore) { bind<ApprovalStateStore>() }
    single { SubmissionLedger() }

    factory<BindingFactsProvider> { (session: InstallerSessionRepository) ->
        SessionBindingFactsProvider(session)
    }

    factory<InstallationGateway> { (session: InstallerSessionRepository) ->
        SessionInstallationGateway(session)
    }

    factory<ApprovalCoordinator> { (session: InstallerSessionRepository) ->
        ApprovalCoordinator(
            store = get(),
            ledger = get(),
            gateway = get<InstallationGateway> { parametersOf(session) },
            factsProvider = get<BindingFactsProvider> { parametersOf(session) },
        )
    }
}
