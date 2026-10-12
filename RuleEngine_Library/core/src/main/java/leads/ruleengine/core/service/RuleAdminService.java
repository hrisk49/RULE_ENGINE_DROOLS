package leads.ruleengine.core.service;

import jakarta.transaction.Transactional;
import leads.ruleengine.core.dto.BuilderRuleSpec;
import leads.ruleengine.core.dto.RuleDefinitionRequest;
import leads.ruleengine.core.dto.RulePreviewResponse;
import leads.ruleengine.core.exception.RuleCompilationException;
import leads.ruleengine.core.exception.RuleNotFoundException;
import leads.ruleengine.core.model.RuleDefinition;
import leads.ruleengine.core.repository.RuleDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages stored rules ({@code RULE_DEFINITION}) as VERSIONS: save a draft, activate a version,
 * deactivate a rule, list.
 *
 * <p><b>Versioning:</b> every save inserts a new, immutable row (ruleName, version), numbered
 * per rule name. A new version is a {@code DRAFT} and is <i>not</i> live; it goes live only through
 * {@link #activate(String, int)}, which makes it the single active version of its rule name and
 * archives the previous one. Rolling back is just activating an older version. Saving therefore
 * never changes live behaviour on its own.</p>
 *
 * <p><b>Two source types:</b>
 * <ul>
 *   <li>{@code BUILDER} - the payload carries {@code sourceJson} from the Visual Rule Builder; the
 *       {@link RuleDrlGenerator} turns it into the {@code drlText} the engine runs. The JSON is
 *       kept so the rule can be re-opened and edited in the builder.</li>
 *   <li>{@code DRL} - the payload carries hand-written {@code drl}; it is stored as-is with no JSON.</li>
 * </ul>
 *
 * <p><b>Validate-before-swap:</b> a version is compiled together with the other live rules
 * before it is saved (so a broken draft is rejected up front) and again before it is activated.
 * Only after a successful activation is the rule base reloaded; a rule that does not compile
 * never reaches the running engine.</p>
 *
 * @author K M Farhat Snigdah
 */
@Service
public class RuleAdminService {

    private static final Logger log = LoggerFactory.getLogger(RuleAdminService.class);

    private final RuleDefinitionRepository ruleDefinitionRepository;
    private final RuleBaseProvider ruleBaseProvider;
    private final RuleDrlGenerator drlGenerator;

    public RuleAdminService(RuleDefinitionRepository ruleDefinitionRepository,
                            RuleBaseProvider ruleBaseProvider,
                            RuleDrlGenerator drlGenerator) {
        this.ruleDefinitionRepository = ruleDefinitionRepository;
        this.ruleBaseProvider = ruleBaseProvider;
        this.drlGenerator = drlGenerator;
    }

    /**
     * Save a new version of a rule as a DRAFT (version = newest + 1, or 1 for a new rule).
     * Generates DRL from JSON for BUILDER rules and checks that it would compile alongside the
     * live rules. The live rule base is not touched - activate the version to apply it.
     */
    @Transactional
    public RuleDefinition save(RuleDefinitionRequest request) {
        List<RuleDefinition> versions = ruleDefinitionRepository.findByRuleNameOrderByVersionDesc(request.ruleName());
        RuleDefinition newest = versions.isEmpty() ? null : versions.get(0);

        // The stage (agenda group) is part of a rule's identity across its versions.
        if (newest != null && !newest.getAgendaGroup().equals(request.agendaGroup())) {
            throw new IllegalArgumentException("Rule '" + request.ruleName() + "' belongs to agenda group '"
                    + newest.getAgendaGroup() + "'; a new version cannot move it to '" + request.agendaGroup()
                    + "'. Create a rule with a new name instead.");
        }

        RuleDefinition draft = new RuleDefinition();
        draft.setRuleName(request.ruleName());
        draft.setAgendaGroup(request.agendaGroup());

        String sourceType = request.sourceType() == null
                ? RuleDefinition.SOURCE_DRL
                : request.sourceType().trim().toUpperCase();
        draft.setSourceType(sourceType);

        if (RuleDefinition.SOURCE_BUILDER.equals(sourceType)) {
            if (isBlank(request.sourceJson())) {
                throw new IllegalArgumentException("sourceJson is required when sourceType is BUILDER.");
            }
            draft.setSourceJson(request.sourceJson());
            draft.setDrlText(drlGenerator.fromJson(request.sourceJson()));
        } else {
            if (isBlank(request.drl())) {
                throw new IllegalArgumentException("drl is required when sourceType is DRL.");
            }
            draft.setSourceJson(null);
            draft.setDrlText(request.drl());
        }

        LocalDateTime now = LocalDateTime.now();
        draft.setVersion(newest == null ? 1 : newest.getVersion() + 1);
        draft.setStatus(RuleDefinition.STATUS_DRAFT);
        draft.setActive(false);
        draft.setCreatedBy(request.createdBy());
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);

        // Reject a version that could never be activated: compile it as if it replaced the live one.
        ruleBaseProvider.validate(candidateActiveSet(draft));

        RuleDefinition saved = ruleDefinitionRepository.save(draft);
        log.info("Rule '{}' v{} saved as DRAFT (type {}); live rule base unchanged until it is activated.",
                saved.getRuleName(), saved.getVersion(), saved.getSourceType());
        return saved;
    }

    /**
     * Make one version the live version of its rule: the previously active version (if any) is
     * archived, this one becomes ACTIVE, and the rule base is reloaded. Works for any version -
     * a draft (release) or an older archived one (rollback). Re-activating the live version is a no-op.
     */
    @Transactional
    public RuleDefinition activate(String ruleName, int version) {
        RuleDefinition target = ruleDefinitionRepository.findByRuleNameAndVersion(ruleName, version)
                .orElseThrow(() -> new RuleNotFoundException("Rule '" + ruleName + "' has no version " + version + "."));
        if (target.isActive()) {
            return target;
        }

        // Compile the live set as it would look with this version in place, before changing anything.
        ruleBaseProvider.validate(candidateActiveSet(target));

        LocalDateTime now = LocalDateTime.now();
        ruleDefinitionRepository.findByRuleNameAndActiveTrue(ruleName).ifPresent(current -> {
            current.setActive(false);
            current.setStatus(RuleDefinition.STATUS_ARCHIVED);
            current.setUpdatedAt(now);
            // Flush first: at most one active row per rule name (unique index), so the old one must
            // be switched off in the database before the new one is switched on.
            ruleDefinitionRepository.saveAndFlush(current);
        });

        target.setActive(true);
        target.setStatus(RuleDefinition.STATUS_ACTIVE);
        target.setUpdatedAt(now);
        RuleDefinition saved = ruleDefinitionRepository.saveAndFlush(target);

        ruleBaseProvider.reload();
        log.info("Rule '{}' v{} activated; rule base reloaded.", ruleName, version);
        return saved;
    }

    /** All versions of a rule, newest first (empty if the rule does not exist). */
    public List<RuleDefinition> versions(String ruleName) {
        return ruleDefinitionRepository.findByRuleNameOrderByVersionDesc(ruleName);
    }

    /**
     * Deactivate a rule (soft delete): every version is archived, so the rule leaves the live rule
     * base and drops out of the active list. Any version can still be re-activated later.
     */
    @Transactional
    public void delete(String ruleName) {
        List<RuleDefinition> versions = ruleDefinitionRepository.findByRuleNameOrderByVersionDesc(ruleName);
        if (versions.isEmpty()) {
            return;
        }
        boolean wasLive = versions.stream().anyMatch(RuleDefinition::isActive);
        LocalDateTime now = LocalDateTime.now();
        for (RuleDefinition v : versions) {
            if (v.isActive() || !RuleDefinition.STATUS_ARCHIVED.equals(v.getStatus())) {
                v.setActive(false);
                v.setStatus(RuleDefinition.STATUS_ARCHIVED);
                v.setUpdatedAt(now);
            }
        }
        ruleDefinitionRepository.saveAll(versions);
        if (wasLive) {
            ruleBaseProvider.reload();
        }
        log.info("Rule '{}' deactivated ({} version(s) archived){}.", ruleName, versions.size(),
                wasLive ? "; rule base reloaded" : "");
    }

    /**
     * Preview: generate the DRL for a builder spec WITHOUT saving, and compile-check it so the UI
     * can show the generated DRL and whether it is valid. The live engine is never touched.
     */
    public RulePreviewResponse preview(BuilderRuleSpec spec) {
        String drl;
        try {
            drl = drlGenerator.generate(spec);
        } catch (RuntimeException e) {
            return new RulePreviewResponse(null, false, e.getMessage());
        }
        try {
            RuleDefinition probe = new RuleDefinition();
            probe.setRuleName(isBlank(spec.ruleName) ? "preview" : spec.ruleName);
            probe.setAgendaGroup(isBlank(spec.agendaGroup) ? "PREVIEW" : spec.agendaGroup);
            probe.setSourceType(RuleDefinition.SOURCE_BUILDER);
            probe.setDrlText(drl);
            ruleBaseProvider.validate(List.of(probe));
            return new RulePreviewResponse(drl, true, null);
        } catch (RuleCompilationException e) {
            return new RulePreviewResponse(drl, false, e.getMessage());
        }
    }

    /**
     * Every version row (live, draft and archived), grouped by rule name, newest first. Clients
     * that only want live rules filter on {@code active}; the builder UI uses the rest for history
     * and "draft pending" indicators.
     */
    public List<RuleDefinition> list() {
        return ruleDefinitionRepository.findAllByOrderByRuleNameAscVersionDesc();
    }

    /**
     * The active set as it WOULD look with the candidate applied: all currently-active rules,
     * with the candidate replacing any same-named entry (or added if new).
     */
    private List<RuleDefinition> candidateActiveSet(RuleDefinition candidate) {
        List<RuleDefinition> set = new ArrayList<>();
        for (RuleDefinition existing : ruleDefinitionRepository.findByActiveTrue()) {
            if (!existing.getRuleName().equals(candidate.getRuleName())) {
                set.add(existing);
            }
        }
        set.add(candidate);
        return set;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
