package pdp.service_bron.telegram;

/** Escapes user-provided text for Telegram's HTML parse mode. */
public final class HtmlEscaper {

    private HtmlEscaper() {
    }

    public static String esc(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** HTML mention that opens the user's profile: {@code <a href="tg://user?id=...">Name</a>}. */
    public static String mention(long telegramId, String name) {
        return "<a href=\"tg://user?id=" + telegramId + "\">" + esc(name) + "</a>";
    }
}
