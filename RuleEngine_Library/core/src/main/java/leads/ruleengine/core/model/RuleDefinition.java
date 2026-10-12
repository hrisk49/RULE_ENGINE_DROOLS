package leads.ruleengine.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * One rule, stored in the database so rules can be changed at RUNTIME (save a new version, the
 * engine validates and hot-swaps it - no restart, no redeploy).
 *
 * <p>This replaces the older "one DRL file per row" model. A rule now carries BOTH forms:
 * <ul>
 *   <li>{@code sourceJson} - how the rule was authored in the Visual Rule Builder (the editable
 *       source of truth). {@code null} for rules a developer hand-wrote in DRL.</li>
 *   <li>{@code drlText} - the executable DRL. For a BUILDER rule the backend generates it from
 *       {@code sourceJson}; for a DRL rule it is written by hand. <b>The engine only ever runs
 *       {@code drlText}.</b></li>
 * </ul>
 *
 * <p><b>A rule belongs to one group, never a flow.</b> {@code agendaGroup} is the only routing
 * link a rule stores. A flow (see {@link FlowGroup}) pulls in groups, so a rule placed in a
 * shared group (e.g. {@code COMMON}) is reused by every flow that includes that group - with no
 * duplication and no many-to-many table.</p>
 *
 * <p><b>Oracle notes:</b> {@code sourceJson}/{@code drlText} are {@link Lob} (CLOB); the id uses
 * a {@code SEQUENCE}; {@code updatedAt} is a {@link LocalDateTime} so it maps to a plain
 * {@code TIMESTAMP} (an {@code Instant} would be read as timestamp-with-time-zone and throw
 * {@code ORA-18716}); the version column is {@code VERSION_NO} to avoid clashing with JPA's
 * optimistic-locking {@code @Version}.</p>
 *
 * @author K M Farhat Snigdah
 */
@Entity
@Table(name = "RULE_DEFINITION",
        uniqueConstraints = @UniqueConstraint(name = "UK_RULE_DEFINITION_NAME_VER", columnNames = {"RULE_NAME", "VERSION_NO"}))
public class RuleDefinition {

    /** Rule was hand-written in DRL by a developer. {@code sourceJson} is null. */
    public static final String SOURCE_DRL = "DRL";
    /** Rule was authored in the Visual Rule Builder. {@code drlText} is generated from {@code sourceJson}. */
    public static final String SOURCE_BUILDER = "BUILDER";

    // ---- version lifecycle ----
    // Every save inserts a new immutable row (RULE_NAME, VERSION_NO). Exactly one version per
    // rule name is ACTIVE (active = true) - only that one is compiled into the live rule base.
    /** Saved but not live; becomes live only through an explicit activation. */
    public static final String STATUS_DRAFT = "DRAFT";
    /** The live version of its rule name (active = true). */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** A former live version, a superseded draft, or a deleted rule. Can be re-activated (rollback). */
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "rule_definition_seq")
    @SequenceGenerator(name = "rule_definition_seq", sequenceName = "RULE_DEFINITION_SEQ", allocationSize = 1)
    private Long id;

    /**
     * Human-readable rule name - the identity of a rule across its versions (unique together with
     * {@code VERSION_NO}). Also used as the DRL resource name when compiling the active version.
     */
    @Column(name = "RULE_NAME", nullable = false, length = 255)
    private String ruleName;

    /** The single group this rule belongs to (e.g. COMMON, TRANSFER, LOAN). */
    @Column(name = "AGENDA_GROUP", nullable = false, length = 255)
    private String agendaGroup;

    /** {@link #SOURCE_DRL} or {@link #SOURCE_BUILDER}. */
    @Column(name = "SOURCE_TYPE", nullable = false, length = 20)
    private String sourceType = SOURCE_DRL;

    /** The builder's saved form (editable). Null for hand-written DRL rules. */
    @Lob
    @Column(name = "SOURCE_JSON")
    private String sourceJson;

    /** The executable DRL. This is the only thing the engine compiles and runs. */
    @Lob
    @Column(name = "DRL_TEXT", nullable = false)
    private String drlText;

    /** Version number within the rule name: 1, 2, 3 … (each save creates the next one). */
    @Column(name = "VERSION_NO", nullable = false)
    private Integer version = 1;

    /** {@link #STATUS_DRAFT}, {@link #STATUS_ACTIVE} or {@link #STATUS_ARCHIVED}. */
    @Column(name = "STATUS", nullable = false, length = 20)
    private String status = STATUS_DRAFT;

    /**
     * True for the single live version of a rule name. Only active rows are compiled into the
     * running rule base (see {@code RuleBaseProvider}); drafts and archived versions never are.
     */
    @Column(name = "ACTIVE", nullable = false)
    private boolean active = false;

    /** Who saved this version (optional - filled from the admin call when available). */
    @Column(name = "CREATED_BY", length = 100)
    private String createdBy;

    /** When this version was saved. Never changes after insert. */
    @Column(name = "CREATED_AT")
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Last change to this row (creation, or activation / archiving). */
    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getAgendaGroup() {
        return agendaGroup;
    }

    public void setAgendaGroup(String agendaGroup) {
        this.agendaGroup = agendaGroup;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceJson() {
        return sourceJson;
    }

    public void setSourceJson(String sourceJson) {
        this.sourceJson = sourceJson;
    }

    public String getDrlText() {
        return drlText;
    }

    public void setDrlText(String drlText) {
        this.drlText = drlText;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
