package leads.ruleengine.core.service;

import leads.ruleengine.core.dto.RuleDefinitionRequest;
import leads.ruleengine.core.exception.RuleCompilationException;
import leads.ruleengine.core.exception.RuleNotFoundException;
import leads.ruleengine.core.model.RuleDefinition;
import leads.ruleengine.core.repository.RuleDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.definition.rule.Rule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rule version history against a real JPA repository (H2) and real Drools compilation:
 * saves create DRAFT versions, activation flips the single live version, and the live rule base
 * only ever contains active versions.
 */
@DataJpaTest
@Import({RuleAdminService.class, RuleBaseProvider.class, RuleDrlGenerator.class})
class RuleVersioningTest {

    @Autowired
    private RuleAdminService service;
    @Autowired
    private RuleBaseProvider ruleBase;
    @Autowired
    private RuleDefinitionRepository repository;

    /** The DB rolls back after each test but the rule-base bean is shared - rebuild it from the clean DB. */
    @BeforeEach
    void resetRuleBase() {
        ruleBase.reload();
    }

    /** A hand-written DRL rule; the DRL rule name tells us which version got compiled. */
    private static RuleDefinitionRequest drl(String ruleName, String group, String drlRuleName) {
        String drl = "package test.rules;\n\n"
                + "rule \"" + drlRuleName + "\"\n"
                + "    agenda-group \"" + group + "\"\n"
                + "when\n"
                + "then\n"
                + "end\n";
        return new RuleDefinitionRequest(ruleName, group, "DRL", null, drl, "tester");
    }

    /** DRL rule names currently compiled into the LIVE rule base. */
    private Set<String> liveDrlRules() {
        return ruleBase.getContainer().getKieBase().getKiePackages().stream()
                .flatMap(p -> p.getRules().stream())
                .map(Rule::getName)
                .collect(Collectors.toSet());
    }

    private List<String> statuses(String ruleName) {
        return service.versions(ruleName).stream()
                .map(v -> "v" + v.getVersion() + ":" + v.getStatus() + (v.isActive() ? ":active" : ""))
                .toList();
    }

    // ---- (a) saving creates versions, never live changes ----------------------------------

    @Test
    void savingTwiceCreatesTwoDraftVersionsAndLeavesTheLiveRuleBaseAlone() {
        service.save(drl("Limit", "TEST", "limit v1"));
        service.save(drl("Limit", "TEST", "limit v2"));

        assertThat(repository.findByRuleNameOrderByVersionDesc("Limit")).hasSize(2);
        assertThat(statuses("Limit")).containsExactly("v2:DRAFT", "v1:DRAFT");
        assertThat(repository.findByRuleNameAndActiveTrue("Limit")).isEmpty();
        assertThat(liveDrlRules()).isEmpty();
    }

    @Test
    void savingAgainAfterActivationAddsOnlyADraftAndKeepsTheLiveVersion() {
        service.save(drl("Limit", "TEST", "limit v1"));
        service.activate("Limit", 1);

        RuleDefinition v2 = service.save(drl("Limit", "TEST", "limit v2"));

        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.isActive()).isFalse();
        assertThat(statuses("Limit")).containsExactly("v2:DRAFT", "v1:ACTIVE:active");
        assertThat(liveDrlRules()).containsExactly("limit v1");
    }

    // ---- (b) activation flips exactly one version ------------------------------------------

    @Test
    void activatingAnOlderVersionFlipsTheLiveVersionAndChangesNothingElse() {
        service.save(drl("Other", "TEST", "other v1"));
        service.activate("Other", 1);
        service.save(drl("Limit", "TEST", "limit v1"));
        service.activate("Limit", 1);
        service.save(drl("Limit", "TEST", "limit v2"));
        service.activate("Limit", 2);
        assertThat(statuses("Limit")).containsExactly("v2:ACTIVE:active", "v1:ARCHIVED");

        List<String> drlBefore = service.versions("Limit").stream().map(RuleDefinition::getDrlText).toList();

        RuleDefinition rolledBack = service.activate("Limit", 1);   // roll back to v1

        assertThat(rolledBack.getVersion()).isEqualTo(1);
        assertThat(statuses("Limit")).containsExactly("v2:ARCHIVED", "v1:ACTIVE:active");
        assertThat(service.versions("Limit").stream().map(RuleDefinition::getDrlText).toList()).isEqualTo(drlBefore);
        assertThat(statuses("Other")).containsExactly("v1:ACTIVE:active");          // other rules untouched
        assertThat(liveDrlRules()).containsExactlyInAnyOrder("other v1", "limit v1");
    }

    @Test
    void activatingTheLiveVersionAgainIsANoOp() {
        service.save(drl("Limit", "TEST", "limit v1"));
        service.activate("Limit", 1);
        service.activate("Limit", 1);
        assertThat(statuses("Limit")).containsExactly("v1:ACTIVE:active");
    }

    @Test
    void activatingAMissingVersionIsNotFound() {
        service.save(drl("Limit", "TEST", "limit v1"));
        assertThatThrownBy(() -> service.activate("Limit", 9)).isInstanceOf(RuleNotFoundException.class);
    }

    // ---- (c) the live rule base only ever contains active versions --------------------------

    @Test
    void ruleBaseNeverIncludesDraftOrArchivedVersions() {
        service.save(drl("Limit", "TEST", "limit v1"));
        assertThat(liveDrlRules()).isEmpty();                                  // draft only

        service.activate("Limit", 1);
        assertThat(liveDrlRules()).containsExactly("limit v1");

        service.save(drl("Limit", "TEST", "limit v2"));
        service.save(drl("Limit", "TEST", "limit v3"));
        ruleBase.reload();                                                     // any rebuild
        assertThat(liveDrlRules()).containsExactly("limit v1");               // drafts never leak

        service.activate("Limit", 3);
        assertThat(liveDrlRules()).containsExactly("limit v3");               // archived v1 gone

        service.delete("Limit");
        assertThat(liveDrlRules()).isEmpty();
        assertThat(statuses("Limit")).containsExactly("v3:ARCHIVED", "v2:ARCHIVED", "v1:ARCHIVED");
    }

    @Test
    void firedRuleNamesResolveToTheActiveVersion() {
        service.save(drl("Limit", "TEST", "limit rule"));
        service.save(drl("Limit", "TEST", "limit rule"));
        service.activate("Limit", 2);

        assertThat(ruleBase.sourceOf("limit rule")).contains(new RuleVersionRef("Limit", 2));
        assertThat(ruleBase.sourceOf("unknown")).isEmpty();
    }

    // ---- guards ----------------------------------------------------------------------------

    @Test
    void aNewVersionCannotMoveTheRuleToAnotherAgendaGroup() {
        service.save(drl("Limit", "TEST", "limit v1"));
        assertThatThrownBy(() -> service.save(drl("Limit", "OTHER", "limit v2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agenda group");
        assertThat(repository.findByRuleNameOrderByVersionDesc("Limit")).hasSize(1);
    }

    @Test
    void aDraftThatDoesNotCompileIsRejectedAndNotStored() {
        RuleDefinitionRequest broken = new RuleDefinitionRequest("Broken", "TEST", "DRL", null,
                "package test.rules;\nrule \"broken\" when $x : NoSuchType() then end", "tester");
        assertThatThrownBy(() -> service.save(broken)).isInstanceOf(RuleCompilationException.class);
        assertThat(repository.findByRuleNameOrderByVersionDesc("Broken")).isEmpty();
    }
}
