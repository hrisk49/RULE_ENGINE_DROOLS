package leads.ruleengine.core.service;

import leads.ruleengine.core.context.GlobalContext;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.AgendaGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The method a consuming service calls to run rules. Two overloads so global reference data is
 * OPTIONAL - a service uses it only if its rules need it.
 *
 * <p>Routing is driven by the inserted fact's OWN class: its name selects the flow
 * ({@code REQUEST_FLOW_MAP.FACT_TYPE}), the flow selects the ordered agenda groups, and the
 * session fires them in that order. The service passes only the context - no separate request
 * class.</p>
 *
 * <pre>
 *   ruleExecutionService.execute(context);                 // rules read only the fact
 *   ruleExecutionService.execute(context, globalContext);  // rules also read shared reference data
 * </pre>
 *
 * <p>A fresh {@link KieSession} is created per call (sessions are not thread-safe) and disposed.</p>
 *
 * @author K M Farhat Snigdah
 */
@Service
public class RuleExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RuleExecutionService.class);

    private final RuleBaseProvider ruleBaseProvider;
    private final DecisionFlowResolver flowResolver;

    public RuleExecutionService(RuleBaseProvider ruleBaseProvider,
                                DecisionFlowResolver flowResolver) {
        this.ruleBaseProvider = ruleBaseProvider;
        this.flowResolver = flowResolver;
    }

    /** Run the flow mapped to {@code fact}'s class, WITHOUT any global reference data. */
    public void execute(Object fact) {
        run(fact, null);
    }

    /** Run the flow mapped to {@code fact}'s class, exposing {@code globalContext} to the rules. */
    public void execute(Object fact, GlobalContext globalContext) {
        run(fact, globalContext);
    }

    private void run(Object fact, GlobalContext globalContext) {
        String flow = flowResolver.flowFor(fact.getClass().getName());
        List<String> groups = flowResolver.groupsFor(flow);

        KieSession session = ruleBaseProvider.getContainer().newKieSession();
        try {
            if (globalContext != null) {
                try {
                    session.setGlobal("globalContext", globalContext);
                } catch (RuntimeException ignored) {
                    // DRL did not declare the global - nothing to inject.
                }
            }

            if (log.isDebugEnabled()) {
                // Which rule VERSION fired - the hook a decision log can use to record the exact
                // rule text that was live for this request.
                session.addEventListener(new DefaultAgendaEventListener() {
                    @Override
                    public void afterMatchFired(AfterMatchFiredEvent event) {
                        String drlRule = event.getMatch().getRule().getName();
                        log.debug("Flow {}: fired '{}' ({})", flow, drlRule, ruleBaseProvider.sourceOf(drlRule)
                                .map(ref -> ref.ruleName() + " v" + ref.version()).orElse("version unknown"));
                    }
                });
            }

            session.insert(fact);

            for (int i = groups.size() - 1; i >= 0; i--) {
                AgendaGroup group = session.getAgenda().getAgendaGroup(groups.get(i));
                if (group != null) {
                    group.setFocus();
                }
            }

            session.fireAllRules();
        } finally {
            session.dispose();
        }
    }
}
