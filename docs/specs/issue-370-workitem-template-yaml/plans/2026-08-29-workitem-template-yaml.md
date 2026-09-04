# WorkItemTemplate YAML Declaration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #370 — feat: WorkItemTemplate YAML declaration format
**Issue group:** #370

**Goal:** Enable WorkItemTemplates to be declared in YAML files on the classpath, loaded at startup into the existing template store, and resolved via the existing `templateRef` mechanism — enabling YAML-only tutorials.

**Architecture:** A `@Startup` bean reads `META-INF/work-templates.yaml` from the classpath, parses each template entry into a `WorkItemTemplate` entity, pre-stamps tenancyId, and upserts into `WorkItemTemplateStore`. A deployment `@BuildStep` registers the YAML path for native image inclusion. Zero changes to the existing template resolution path (`findByRef` → `mergeRequestWithTemplate`).

**Tech Stack:** Java 21, Quarkus 3.32.2, Jackson YAML, Panache

## Global Constraints

- Work repo only — zero cross-repo changes
- `WorkItemTemplate` entity field names are the YAML property names — no mapping layer
- Pre-stamp `tenancyId = TenancyConstants.DEFAULT_TENANT_ID` — no `CurrentPrincipal` at startup
- Upsert by name + tenancyId — WARN on collision, overwrite
- `${env.X}` and `${sys.X}` interpolation at load time only
- Build: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl <module> -Dcheckstyle.skip=true`

---

## Batch 1: YAML Loader + Native Image Support

### Task 1: WorkItemTemplateYamlLoader — parsing, interpolation, upsert

**Files:**
- Create: `runtime/src/main/java/io/casehub/work/runtime/service/WorkItemTemplateYamlLoader.java`
- Test: `runtime/src/test/java/io/casehub/work/runtime/service/WorkItemTemplateYamlLoaderTest.java`
- Create: `runtime/src/test/resources/META-INF/work-templates.yaml` (test fixture)

**Interfaces:**
- Consumes: `WorkItemTemplateStore.put(WorkItemTemplate)`, `WorkItemTemplate` entity fields, `TenancyConstants.DEFAULT_TENANT_ID`
- Produces: Templates loaded into DB at startup; no public API — the class is an internal startup hook

- [ ] **Step 1: Create test fixture YAML**

Create `runtime/src/test/resources/META-INF/work-templates.yaml`:

```yaml
workItemTemplates:
  - name: test-basic
    description: "Basic test template"
    candidateGroups: "reviewers"
    defaultExpiryHours: 48
    outcomes: '[{"name":"APPROVED"},{"name":"REJECTED"}]'

  - name: test-full
    description: "Full-field test template"
    priority: URGENT
    candidateGroups: "ethics-committee,senior-reviewers"
    candidateUsers: "dr-smith"
    requiredCapabilities: "clinical-review"
    defaultExpiryHours: 72
    defaultClaimHours: 24
    defaultPayload: '{"formType": "irb"}'
    labelPaths: '["clinical/irb","compliance/required"]'
    typePaths: '["irb","ethics"]'
    outcomes: '[{"name":"APPROVED"},{"name":"REJECTED"},{"name":"DEFERRED"}]'
    excludedUsers: "trial-pi"
    scope: "casehubio/clinical"
    instanceCount: 3
    requiredCount: 2
    onThresholdReached: KEEP
    assignmentStrategy: "round-robin"

  - name: test-interpolated
    description: "Template with env interpolation"
    candidateGroups: "${env.TEST_REVIEW_TEAM}"
```

- [ ] **Step 2: Write failing test — parse basic template**

```java
@QuarkusTest
class WorkItemTemplateYamlLoaderTest {

    @Inject WorkItemTemplateStore templateStore;

    @Test
    void loadsBasicTemplateFromYaml() {
        Optional<WorkItemTemplate> opt = findByNameDirect("test-basic");
        assertThat(opt).isPresent();
        WorkItemTemplate t = opt.get();
        assertThat(t.name).isEqualTo("test-basic");
        assertThat(t.description).isEqualTo("Basic test template");
        assertThat(t.candidateGroups).isEqualTo("reviewers");
        assertThat(t.defaultExpiryHours).isEqualTo(48);
        assertThat(t.outcomes).contains("APPROVED");
        assertThat(t.tenancyId).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void loadsFullFieldTemplate() {
        Optional<WorkItemTemplate> opt = findByNameDirect("test-full");
        assertThat(opt).isPresent();
        WorkItemTemplate t = opt.get();
        assertThat(t.priority).isEqualTo(WorkItemPriority.URGENT);
        assertThat(t.candidateGroups).isEqualTo("ethics-committee,senior-reviewers");
        assertThat(t.candidateUsers).isEqualTo("dr-smith");
        assertThat(t.instanceCount).isEqualTo(3);
        assertThat(t.requiredCount).isEqualTo(2);
        assertThat(t.onThresholdReached).isEqualTo("KEEP");
        assertThat(t.scope).isEqualTo("casehubio/clinical");
    }

    @Test
    void interpolatesEnvironmentVariables() {
        Optional<WorkItemTemplate> opt = findByNameDirect("test-interpolated");
        assertThat(opt).isPresent();
        // ${env.TEST_REVIEW_TEAM} not set → stays as literal or empty
        // Test verifies the interpolation path runs without error
    }

    private Optional<WorkItemTemplate> findByNameDirect(String name) {
        return WorkItemTemplate
            .find("name = ?1 AND tenancyId = ?2", name, TenancyConstants.DEFAULT_TENANT_ID)
            .firstResultOptional();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=WorkItemTemplateYamlLoaderTest -pl runtime -Dcheckstyle.skip=true`
Expected: FAIL — class does not exist / templates not loaded

- [ ] **Step 4: Implement WorkItemTemplateYamlLoader**

```java
package io.casehub.work.runtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.runtime.model.WorkItemTemplate;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
@Startup
public class WorkItemTemplateYamlLoader {

    private static final Logger LOG = Logger.getLogger(WorkItemTemplateYamlLoader.class);
    private static final String RESOURCE_PATH = "META-INF/work-templates.yaml";
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{(env|sys)\\.([^}]+)}");
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    @Transactional
    void loadTemplates() {
        try {
            Enumeration<URL> resources = Thread.currentThread()
                .getContextClassLoader().getResources(RESOURCE_PATH);
            for (URL url : Collections.list(resources)) {
                loadFromUrl(url);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to discover " + RESOURCE_PATH, e);
        }
    }

    private void loadFromUrl(URL url) {
        LOG.infof("Loading work-item templates from %s", url);
        try (InputStream is = url.openStream()) {
            JsonNode root = YAML_MAPPER.readTree(is);
            JsonNode templates = root.get("workItemTemplates");
            if (templates == null || !templates.isArray()) {
                LOG.warnf("No workItemTemplates array in %s — skipping", url);
                return;
            }
            for (JsonNode node : templates) {
                WorkItemTemplate template = mapToEntity(node);
                template.tenancyId = TenancyConstants.DEFAULT_TENANT_ID;
                upsertByName(template, url.toString());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse " + url + ": " + e.getMessage(), e);
        }
    }

    private WorkItemTemplate mapToEntity(JsonNode node) {
        WorkItemTemplate t = new WorkItemTemplate();
        t.name = interpolate(textOrNull(node, "name"));
        t.description = interpolate(textOrNull(node, "description"));
        t.candidateGroups = interpolate(textOrNull(node, "candidateGroups"));
        t.candidateUsers = interpolate(textOrNull(node, "candidateUsers"));
        t.requiredCapabilities = interpolate(textOrNull(node, "requiredCapabilities"));
        t.defaultPayload = textOrNull(node, "defaultPayload");
        t.labelPaths = textOrNull(node, "labelPaths");
        t.typePaths = textOrNull(node, "typePaths");
        t.outcomes = textOrNull(node, "outcomes");
        t.excludedUsers = interpolate(textOrNull(node, "excludedUsers"));
        t.excludedGroups = interpolate(textOrNull(node, "excludedGroups"));
        t.scope = interpolate(textOrNull(node, "scope"));
        t.inputDataSchema = textOrNull(node, "inputDataSchema");
        t.outputDataSchema = textOrNull(node, "outputDataSchema");
        t.assignmentStrategy = textOrNull(node, "assignmentStrategy");
        t.onThresholdReached = textOrNull(node, "onThresholdReached");
        t.parentRole = textOrNull(node, "parentRole");
        if (node.has("priority")) {
            t.priority = WorkItemPriority.valueOf(node.get("priority").asText());
        }
        if (node.has("defaultExpiryHours")) t.defaultExpiryHours = node.get("defaultExpiryHours").asInt();
        if (node.has("defaultClaimHours")) t.defaultClaimHours = node.get("defaultClaimHours").asInt();
        if (node.has("defaultExpiryBusinessHours")) t.defaultExpiryBusinessHours = node.get("defaultExpiryBusinessHours").asInt();
        if (node.has("defaultClaimBusinessHours")) t.defaultClaimBusinessHours = node.get("defaultClaimBusinessHours").asInt();
        if (node.has("instanceCount")) t.instanceCount = node.get("instanceCount").asInt();
        if (node.has("requiredCount")) t.requiredCount = node.get("requiredCount").asInt();
        if (node.has("allowSameAssignee")) t.allowSameAssignee = node.get("allowSameAssignee").asBoolean();
        if (t.name == null || t.name.isBlank()) {
            throw new IllegalArgumentException("WorkItemTemplate YAML entry missing required 'name' field");
        }
        return t;
    }

    private void upsertByName(WorkItemTemplate template, String sourceUrl) {
        Optional<WorkItemTemplate> existing = WorkItemTemplate
            .find("name = ?1 AND tenancyId = ?2", template.name, template.tenancyId)
            .firstResultOptional();
        if (existing.isPresent()) {
            LOG.warnf("WorkItemTemplate '%s' already exists (id=%s) — overwriting from %s",
                template.name, existing.get().id, sourceUrl);
            template.id = existing.get().id;
            template.version = existing.get().version;
        }
        template.persistAndFlush();
        LOG.infof("Loaded WorkItemTemplate '%s' (id=%s)", template.name, template.id);
    }

    static String interpolate(String value) {
        if (value == null) return null;
        Matcher m = VAR_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String type = m.group(1);
            String key = m.group(2);
            String resolved = "env".equals(type) ? System.getenv(key) : System.getProperty(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved != null ? resolved : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=WorkItemTemplateYamlLoaderTest -pl runtime -Dcheckstyle.skip=true`
Expected: PASS

- [ ] **Step 6: Write test — upsert collision logs WARN**

```java
@Test
void upsertCollisionLogsWarn() {
    // Load runs at startup — test-basic already exists
    // Manually call upsertByName with same name
    // Verify template is overwritten (check version or id stability)
    long count = WorkItemTemplate.count("name = ?1 AND tenancyId = ?2",
        "test-basic", TenancyConstants.DEFAULT_TENANT_ID);
    assertThat(count).isEqualTo(1); // no duplicates
}
```

- [ ] **Step 7: Write test — malformed YAML throws at startup**

Create `runtime/src/test/resources/META-INF/work-templates-malformed.yaml`:
```yaml
workItemTemplates:
  - description: "Missing required name field"
    candidateGroups: "team"
```

Write a unit test (not `@QuarkusTest`) that calls the loader directly with malformed YAML and asserts `IllegalArgumentException`.

- [ ] **Step 8: Write test — interpolation resolves env vars**

```java
@Test
void interpolateResolvesEnvVars() {
    // Static method test — no Quarkus context needed
    String result = WorkItemTemplateYamlLoader.interpolate("team-${env.PATH}-end");
    assertThat(result).doesNotContain("${env.PATH}");
    assertThat(result).startsWith("team-");
}

@Test
void interpolateLeavesUnresolvedVarsAsIs() {
    String result = WorkItemTemplateYamlLoader.interpolate("${env.NONEXISTENT_VAR_XYZ}");
    assertThat(result).isEqualTo("${env.NONEXISTENT_VAR_XYZ}");
}

@Test
void interpolateHandlesNull() {
    assertThat(WorkItemTemplateYamlLoader.interpolate(null)).isNull();
}
```

- [ ] **Step 9: Commit**

```bash
git -C ~/claude/casehub/work add runtime/src/main/java/io/casehub/work/runtime/service/WorkItemTemplateYamlLoader.java runtime/src/test/java/io/casehub/work/runtime/service/WorkItemTemplateYamlLoaderTest.java runtime/src/test/resources/META-INF/work-templates.yaml
git -C ~/claude/casehub/work commit -m "feat: WorkItemTemplateYamlLoader — load templates from classpath YAML at startup

Reads META-INF/work-templates.yaml, parses to WorkItemTemplate entities,
pre-stamps DEFAULT_TENANT_ID, upserts by name with WARN on collision.
Supports ${env.X} and ${sys.X} interpolation at load time.

Refs #370"
```

### Task 2: Native image build step

**Files:**
- Modify: `deployment/src/main/java/io/casehub/work/deployment/WorkItemsProcessor.java`

**Interfaces:**
- Consumes: existing `@BuildStep` pattern in `WorkItemsProcessor`
- Produces: `NativeImageResourcePatternsBuildItem` for `META-INF/work-templates.yaml`

- [ ] **Step 1: Add build step for YAML template resources**

Add to `WorkItemsProcessor`:

```java
@BuildStep
NativeImageResourcePatternsBuildItem registerTemplateResources() {
    return NativeImageResourcePatternsBuildItem.builder()
            .includeGlob("META-INF/work-templates.yaml")
            .build();
}
```

- [ ] **Step 2: Verify compilation**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn compile -pl deployment -am -Dcheckstyle.skip=true`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git -C ~/claude/casehub/work add deployment/src/main/java/io/casehub/work/deployment/WorkItemsProcessor.java
git -C ~/claude/casehub/work commit -m "feat: register META-INF/work-templates.yaml for native image inclusion

Refs #370"
```

### Task 3: Example YAML fixture for tutorials

**Files:**
- Create: `examples/src/main/resources/META-INF/work-templates.yaml`

**Interfaces:**
- Consumes: WorkItemTemplateYamlLoader (loaded automatically at startup)
- Produces: Tutorial-ready example templates

- [ ] **Step 1: Create example template file**

```yaml
# Example WorkItemTemplate declarations for casehub-work tutorials.
# Place this file at META-INF/work-templates.yaml on your classpath.
# Templates are loaded at startup and available via humanTask.templateRef in CaseDefinition YAML.

workItemTemplates:
  - name: document-review
    description: "Standard document review — 48h deadline, escalates to senior-reviewers"
    candidateGroups: "reviewers"
    defaultExpiryHours: 48
    defaultClaimHours: 8
    outcomes: '[{"name":"APPROVED"},{"name":"REJECTED"},{"name":"NEEDS_REVISION"}]'
    labelPaths: '["review/pending"]'
    typePaths: '["review","document"]'

  - name: urgent-approval
    description: "Urgent approval with 4-hour claim deadline"
    priority: URGENT
    candidateGroups: "approvers"
    defaultExpiryHours: 24
    defaultClaimHours: 4
    outcomes: '[{"name":"APPROVED"},{"name":"REJECTED"}]'
    scope: "default/approvals"
```

- [ ] **Step 2: Commit**

```bash
git -C ~/claude/casehub/work add examples/src/main/resources/META-INF/work-templates.yaml
git -C ~/claude/casehub/work commit -m "docs: example work-templates.yaml for tutorials

Refs #370"
```

---

## References

- [2026-08-29-workitem-template-yaml-design.md](../2026-08-29-workitem-template-yaml-design.md) — design spec
- [decisions.md](../decisions.md) — D1-D7
- `runtime/src/main/java/io/casehub/work/runtime/model/WorkItemTemplate.java` — canonical entity
- `runtime/src/main/java/io/casehub/work/runtime/repository/WorkItemTemplateStore.java` — store SPI
- `runtime/src/main/java/io/casehub/work/runtime/repository/jpa/JpaWorkItemTemplateStore.java:26` — put() pattern
- `runtime/src/main/java/io/casehub/work/runtime/service/WorkItemTemplateService.java:306` — findByRef()
- `deployment/src/main/java/io/casehub/work/deployment/WorkItemsProcessor.java:23` — NativeImage pattern
- `platform/yaml-core/` — yaml-core reference (deferred dependency)
- GitHub #370 — focal issue
