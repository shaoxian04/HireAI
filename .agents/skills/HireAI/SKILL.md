```markdown
# HireAI Development Patterns

> Auto-generated skill from repository analysis

## Overview

This skill documents the core development patterns, coding conventions, and common workflows for the HireAI repository. The project is written in Java, with a strong focus on Domain-Driven Design (DDD), vertical slice architecture, and clear separation of concerns. It uses conventional commit messages and enforces consistent code style and organization, making it easy for contributors to onboard and follow best practices.

## Coding Conventions

### File Naming

- **Java classes and files:** Use PascalCase.
  - Example: `TaskStatus.java`, `WalletServiceImpl.java`

### Imports

- **Style:** Use relative imports within the Java package structure.
  - Example:
    ```java
    import com.hireai.domain.biz.task.enums.TaskStatus;
    ```

### Exports

- **Style:** Use named exports (Java public classes/interfaces).
  - Example:
    ```java
    public class TaskStatus { ... }
    public interface WalletService { ... }
    ```

### Commit Messages

- **Type:** Conventional commits
- **Prefixes:** `feat`, `docs`, `chore`, `refactor`, `test`
- **Example:**
  ```
  feat(task): add Task aggregate root and persistence layer
  ```

## Workflows

### Add New Domain Aggregate Vertical Slice

**Trigger:** When someone wants to add a new core business entity (aggregate) and expose its API endpoints.  
**Command:** `/new-aggregate`

1. **Define enums and value objects** in the domain model.
   - Example: `TaskStatus.java`, `OutputSpec.java`
2. **Add domain service(s), event(s), and repository contract.**
   - Example: `TaskCreatedEvent.java`, `TaskRepository.java`
3. **Create database migration** for the new table.
   - Example: `V20240601__create_task_table.sql`
4. **Implement JPA persistence:** entity, repository, mapper, implementation.
   - Example: `TaskEntity.java`, `TaskRepositoryImpl.java`
5. **Add application service interfaces and implementations.**
   - Example: `TaskService.java`, `TaskServiceImpl.java`
6. **Define request/response DTOs and converter.**
   - Example: `TaskRequestDTO.java`, `TaskResponseDTO.java`, `TaskConverter.java`
7. **Implement controller** for API endpoints.
   - Example: `TaskController.java`
8. **Write integration tests** for the new aggregate's main flows.
   - Example: `TaskIntegrationTest.java`

**Files Involved:**
- `backend/src/main/java/com/hireai/domain/biz/*/enums/*.java`
- `backend/src/main/java/com/hireai/domain/biz/*/model/*.java`
- `backend/src/main/java/com/hireai/domain/biz/*/event/*.java`
- `backend/src/main/java/com/hireai/domain/biz/*/service/*.java`
- `backend/src/main/java/com/hireai/domain/biz/*/repository/*.java`
- `backend/src/main/resources/db/migration/*.sql`
- `backend/src/main/java/com/hireai/infrastructure/repository/*/*.java`
- `backend/src/main/java/com/hireai/application/biz/*/*.java`
- `backend/src/main/java/com/hireai/application/biz/*/impl/*.java`
- `backend/src/main/java/com/hireai/controller/biz/*/dto/*.java`
- `backend/src/main/java/com/hireai/controller/biz/*/converter/*.java`
- `backend/src/main/java/com/hireai/controller/biz/*/*.java`
- `backend/src/test/java/com/hireai/domain/biz/*/model/*Test.java`
- `backend/src/test/java/com/hireai/*/*IntegrationTest.java`

---

### Update Enum and Database Schema

**Trigger:** When someone needs to change the allowed values or lifecycle of a domain enum (e.g., `TaskStatus`).  
**Command:** `/update-enum-schema`

1. **Update the enum definition** in the domain model.
   - Example:
     ```java
     public enum TaskStatus {
         CREATED, IN_PROGRESS, COMPLETED, CANCELLED
     }
     ```
2. **Update the corresponding database migration** (e.g., CHECK constraint).
   - Example: Add or modify a migration SQL file:
     ```sql
     ALTER TABLE task
     ADD CONSTRAINT chk_task_status CHECK (status IN ('CREATED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'));
     ```
3. **Update documentation** to reflect the new lifecycle or allowed values.
   - Edit: `docs/details/data-model.md`

**Files Involved:**
- `backend/src/main/java/com/hireai/domain/biz/*/enums/*.java`
- `backend/src/main/resources/db/migration/*.sql`
- `docs/details/data-model.md`

---

### Add App Service Interface and Implementation

**Trigger:** When someone wants to add or refactor an application service for a domain (e.g., Wallet, Task).  
**Command:** `/new-app-service`

1. **Create or split interface(s)** for the app service (e.g., Read/Write).
   - Example:
     ```java
     public interface WalletService { ... }
     public interface WalletReadService { ... }
     ```
2. **Create or update implementation class(es)** in the corresponding `impl/` directory.
   - Example:
     ```java
     public class WalletServiceImpl implements WalletService { ... }
     ```

**Files Involved:**
- `backend/src/main/java/com/hireai/application/biz/*/*.java`
- `backend/src/main/java/com/hireai/application/biz/*/impl/*.java`

---

## Testing Patterns

- **Framework:** Unknown (likely JUnit or similar for Java)
- **Test File Patterns:**  
  - Unit tests: `*Test.java` (e.g., `TaskModelTest.java`)
  - Integration tests: `*IntegrationTest.java` (e.g., `TaskIntegrationTest.java`)
- **Placement:**  
  - Unit tests in `backend/src/test/java/com/hireai/domain/biz/*/model/`
  - Integration tests in `backend/src/test/java/com/hireai/*/`

**Example:**
```java
import org.junit.jupiter.api.Test;

public class TaskModelTest {
    @Test
    void shouldCreateTaskWithValidStatus() {
        Task task = new Task("Example", TaskStatus.CREATED);
        assertEquals(TaskStatus.CREATED, task.getStatus());
    }
}
```

## Commands

| Command             | Purpose                                                                 |
|---------------------|-------------------------------------------------------------------------|
| /new-aggregate      | Scaffold a new domain aggregate with full vertical slice and tests       |
| /update-enum-schema | Update a domain enum and synchronize DB schema and documentation         |
| /new-app-service    | Add or refactor an application service interface and its implementation  |
```
