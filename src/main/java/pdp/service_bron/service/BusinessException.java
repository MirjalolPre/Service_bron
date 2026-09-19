package pdp.service_bron.service;

/**
 * A user-facing validation failure. The message is an i18n key; the dispatcher translates it into the
 * user's language and sends it as a normal message.
 */
public class BusinessException extends RuntimeException {

    private final transient Object[] args;

    public BusinessException(String messageKey, Object... args) {
        super(messageKey);
        this.args = args;
    }

    public String key() {
        return getMessage();
    }

    public Object[] args() {
        return args;
    }
}
