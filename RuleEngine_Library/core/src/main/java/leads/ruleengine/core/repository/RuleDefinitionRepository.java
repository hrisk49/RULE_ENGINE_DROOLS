package leads.ruleengine.core.repository;

import leads.ruleengine.core.model.RuleDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Access to stored rules ({@code RULE_DEFINITION}). One row per rule VERSION, identified by
 * (ruleName, version); at most one version per rule name is active.
 *
 * @author K M Farhat Snigdah
 */
public interface RuleDefinitionRepository extends JpaRepository<RuleDefinition, Long> {

    /** Active rules only - these are what the running rule base is built from. */
    List<RuleDefinition> findByActiveTrue();

    /** Every version row, grouped by rule name, newest version first (admin listing). */
    List<RuleDefinition> findAllByOrderByRuleNameAscVersionDesc();

    /** All versions of one rule, newest first. */
    List<RuleDefinition> findByRuleNameOrderByVersionDesc(String ruleName);

    /** The newest version of a rule (used to number the next one). */
    Optional<RuleDefinition> findTopByRuleNameOrderByVersionDesc(String ruleName);

    /** The live version of a rule, if any. */
    Optional<RuleDefinition> findByRuleNameAndActiveTrue(String ruleName);

    Optional<RuleDefinition> findByRuleNameAndVersion(String ruleName, Integer version);
}
