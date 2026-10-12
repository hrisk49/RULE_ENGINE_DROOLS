package leads.ruleengine.core.service;

import jakarta.annotation.PostConstruct;
import leads.ruleengine.core.exception.RuleCompilationException;
import leads.ruleengine.core.model.RuleDefinition;
import leads.ruleengine.core.repository.RuleDefinitionRepository;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the live Drools rule base and rebuilds it from the database at RUNTIME.
 *
 * <p>The source is every active {@link RuleDefinition}: the engine compiles each rule's
 * {@code drlText} (the only form it runs) into one container.</p>
 *
 * <p><b>The safety guarantee:</b> a candidate set is compiled into a throw-away container FIRST
 * ({@link #validate(List)}). Only if that succeeds does {@link #reload()} build a fresh container
 * and atomically swap it in. A rule that does not compile is rejected before it can touch the
 * running engine - so a bad save can never break rules that are already live.</p>
 *
 * <p>The container reference is {@code volatile}: {@link #getContainer()} always returns the
 * current one, and readers in flight keep using the old container until they finish.</p>
 *
 * @author K M Farhat Snigdah
 */
@Component
public class RuleBaseProvider {

    private static final Logger log = LoggerFactory.getLogger(RuleBaseProvider.class);

    /** Where each DRL is placed in the virtual KieFileSystem before compilation. */
    private static final String RULES_PATH = "src/main/resources/rules/";

    private final RuleDefinitionRepository ruleDefinitionRepository;
    private final KieServices kieServices = KieServices.Factory.get();

    /** Unique release id per build so containers never collide in the KIE repository. */
    private final AtomicLong buildCounter = new AtomicLong();

    private volatile KieContainer container;

    /** DRL rule name -> the stored rule version it came from, for the rules in {@link #container}. */
    private volatile Map<String, RuleVersionRef> firedRuleIndex = Map.of();

    /** {@code rule "Name"}, {@code rule 'Name'} or {@code rule Name} at the start of a line. */
    private static final Pattern DRL_RULE_NAME =
            Pattern.compile("^\\s*rule\\s+(?:\"([^\"]+)\"|'([^']+)'|(\\S+))", Pattern.MULTILINE);

    public RuleBaseProvider(RuleDefinitionRepository ruleDefinitionRepository) {
        this.ruleDefinitionRepository = ruleDefinitionRepository;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** The current rule base. A new {@code KieSession} is created per request from this. */
    public KieContainer getContainer() {
        return container;
    }

    /**
     * Rebuild the rule base from the ACTIVE version of each rule and swap it in atomically.
     * Called at startup and after every activation / delete. Draft and archived versions are
     * never compiled into the live rule base.
     */
    public synchronized void reload() {
        List<RuleDefinition> active = liveVersions(ruleDefinitionRepository.findByActiveTrue());
        if (active.isEmpty()) {
            log.warn("No active rules - rule base is empty. Rules will fire nothing until one is activated.");
        }
        KieContainer fresh = build(active);
        KieContainer old = this.container;
        this.container = fresh;
        this.firedRuleIndex = indexDrlRuleNames(active);
        if (old != null) {
            old.dispose();
        }
        log.info("Rule base reloaded from {} active rule(s).", active.size());
    }

    /**
     * Which stored rule version a fired DRL rule came from (e.g. for decision logs), looked up by
     * the name in the DRL ({@code rule "..."}). Empty if the name is unknown to the live rule base.
     */
    public Optional<RuleVersionRef> sourceOf(String drlRuleName) {
        return Optional.ofNullable(firedRuleIndex.get(drlRuleName));
    }

    /**
     * Defensive: exactly one version per rule name may be live. The database enforces this with a
     * unique index; if a schema without it ever holds two active rows for a name, keep only the
     * newest so the live set is deterministic, and log it.
     */
    private List<RuleDefinition> liveVersions(List<RuleDefinition> rows) {
        Map<String, RuleDefinition> newestPerName = new LinkedHashMap<>();
        for (RuleDefinition r : rows) {
            if (!r.isActive()) {
                continue;
            }
            RuleDefinition seen = newestPerName.get(r.getRuleName());
            if (seen != null) {
                log.error("Rule '{}' has more than one active version (v{} and v{}); using the newest.",
                        r.getRuleName(), seen.getVersion(), r.getVersion());
            }
            if (seen == null || r.getVersion() > seen.getVersion()) {
                newestPerName.put(r.getRuleName(), r);
            }
        }
        return new ArrayList<>(newestPerName.values());
    }

    private Map<String, RuleVersionRef> indexDrlRuleNames(List<RuleDefinition> rules) {
        Map<String, RuleVersionRef> index = new HashMap<>();
        for (RuleDefinition rule : rules) {
            Matcher m = DRL_RULE_NAME.matcher(rule.getDrlText() == null ? "" : rule.getDrlText());
            while (m.find()) {
                String name = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                index.put(name, new RuleVersionRef(rule.getRuleName(), rule.getVersion()));
            }
        }
        return Map.copyOf(index);
    }

    /**
     * Compile a candidate set WITHOUT swapping the live container. Used to validate a save
     * before committing it. Throws {@link RuleCompilationException} on any compile error;
     * disposes the throw-away container either way.
     */
    public void validate(List<RuleDefinition> candidates) {
        KieContainer probe = build(candidates);
        probe.dispose();
    }

    /**
     * Compile the given rules into a fresh {@link KieContainer}. Shared by {@link #reload()}
     * and {@link #validate(List)}.
     */
    private KieContainer build(List<RuleDefinition> rules) {
        // Every build gets a UNIQUE release id. This is what keeps validate() safe: a probe
        // compile never overwrites the live module in the shared KIE repository, because it
        // deploys under its own version. reload() then swaps to its own fresh version.
        ReleaseId releaseId = kieServices.newReleaseId(
                "leads.ruleengine.runtime", "rules", "1.0." + buildCounter.incrementAndGet());

        KieFileSystem kfs = kieServices.newKieFileSystem();
        kfs.generateAndWritePomXML(releaseId);
        for (RuleDefinition rule : rules) {
            // Each rule becomes its own .drl resource; DRL type is inferred from the extension.
            kfs.write(RULES_PATH + resourceName(rule), rule.getDrlText());
        }

        KieBuilder kieBuilder = kieServices.newKieBuilder(kfs);
        kieBuilder.buildAll();

        List<Message> errors = kieBuilder.getResults().getMessages(Message.Level.ERROR);
        if (!errors.isEmpty()) {
            throw new RuleCompilationException("DRL compilation failed: " + errors);
        }

        return kieServices.newKieContainer(releaseId);
    }

    /** A filesystem-safe, unique .drl resource name derived from the (unique) rule name. */
    private String resourceName(RuleDefinition rule) {
        String safe = rule.getRuleName().replaceAll("[^A-Za-z0-9_]", "_");
        return safe + ".drl";
    }
}
