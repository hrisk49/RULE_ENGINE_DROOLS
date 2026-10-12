package leads.ruleengine.core.service;

/**
 * Identifies the exact stored rule version a fired DRL rule came from - what a decision log
 * needs to reconstruct a decision against the rule text that was live at the time.
 *
 * @param ruleName the stored rule's name ({@code RULE_DEFINITION.RULE_NAME})
 * @param version  the version that was active when the rule fired ({@code VERSION_NO})
 * @author K M Farhat Snigdah
 */
public record RuleVersionRef(String ruleName, int version) {
}
