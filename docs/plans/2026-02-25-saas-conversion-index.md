# JPhotoTagger SaaS Conversion — Implementation Plan Index

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

---

## Phases

| Phase | File | Focus | Tasks |
|-------|------|-------|-------|
| 0 | [Phase 0: Java Upgrade & Gradle Migration](2026-02-25-saas-conversion-phase-0.md) | Java 21 + Gradle migration | 0.1–0.6 |
| 1a | [Phase 1a: Spring Boot Scaffold & Database](2026-02-25-saas-conversion-phase-1a.md) | Spring Boot scaffold, Flyway schema, RLS, worker DB user | 1.1–1.4 |
| 1b | [Phase 1b: Infrastructure](2026-02-25-saas-conversion-phase-1b.md) | Docker Compose stack, Dockerfiles | 1.5–1.6 |
| 2 | [Phase 2: Backend API](2026-02-25-saas-conversion-phase-2.md) | Auth, security, REST endpoints, rate limiting | 2.1–2.9 |
| 3 | [Phase 3: Storage & Media](2026-02-25-saas-conversion-phase-3.md) | MinIO, upload, worker pipeline, scheduled tasks | 3.1–3.6 |
| 4 | [Phase 4: React Frontend](2026-02-25-saas-conversion-phase-4.md) | React frontend — all pages and components | 4.1–4.9 |
| 5 | [Phase 5: Sharing & Polish](2026-02-25-saas-conversion-phase-5.md) | Sharing, Nginx, monitoring, CI/CD, E2E tests | 5.1–5.6 |

**Total: 36 tasks across 6 phases.**

## Phase Dependencies

- **Phase 0** must complete before Phase 1 begins (Java 21 + Gradle build must work first).
- **Phase 1a** must complete before Phase 1b begins (database schema must exist before Docker Compose references it).
- **Phase 1b** must complete before Phase 2 begins (Spring Boot scaffold, database schema, and Docker Compose must be in place).
- **Phase 2** must complete before Phase 3 begins (API endpoints and auth must exist for storage/worker integration).
- **Phase 3** must complete before Phase 4 begins (backend API must be fully functional for frontend integration).
- **Phase 4** must complete before Phase 5 begins (frontend must exist for sharing UI and E2E tests).

## Methodology

Each task follows TDD: write failing test → implement → verify pass → commit.

## Summary

| Phase | Tasks | Focus |
|-------|-------|-------|
| 0 | 0.1–0.6 | Java 21 + Gradle migration |
| 1a | 1.1–1.4 | Spring Boot scaffold, Flyway schema, RLS, worker DB user |
| 1b | 1.5–1.6 | Docker Compose stack, Dockerfiles |
| 2 | 2.1–2.9 | Auth, security, REST endpoints, rate limiting |
| 3 | 3.1–3.6 | MinIO, upload, worker pipeline, scheduled tasks |
| 4 | 4.1–4.9 | React frontend — all pages and components |
| 5 | 5.1–5.6 | Sharing, Nginx, monitoring, CI/CD, E2E tests |

**Total: 36 tasks across 6 phases.**

Each task follows TDD: write failing test → implement → verify pass → commit.

