package leads.ruleengine.core.exception;

/**
 * A rule (or a specific version of it) that an admin call referenced does not exist -> 404.
 *
 * @author K M Farhat Snigdah
 */
public class RuleNotFoundException extends RuntimeException {

    public RuleNotFoundException(String message) {
        super(message);
    }
}
