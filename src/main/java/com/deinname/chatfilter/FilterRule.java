package com.deinname.chatfilter;

import java.util.Locale;

/**
 * A single filter rule with configurable actions.
 * Each rule matches a keyword and can trigger multiple actions.
 */
public class FilterRule {
    private String keyword = "";
    private boolean enabled = true;
    private boolean blockMessage = true;
    private boolean censorWord = false;
    private boolean stripWord = false;
    private boolean showWarning = false;
    private String warningText = "";
    private boolean sendReplacement = false;
    private String replacementText = "";
    private boolean requireConfirmation = false;
    private boolean disconnectFromServer = false;
    private boolean locked = false;
    private int timeoutSeconds = 0;

    public FilterRule() {}

    public FilterRule(String keyword) {
        this.keyword = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
    }

    public FilterRule(FilterRule other) {
        this.keyword = other.keyword;
        this.enabled = other.enabled;
        this.blockMessage = other.blockMessage;
        this.censorWord = other.censorWord;
        this.stripWord = other.stripWord;
        this.showWarning = other.showWarning;
        this.warningText = other.warningText;
        this.sendReplacement = other.sendReplacement;
        this.replacementText = other.replacementText;
        this.requireConfirmation = other.requireConfirmation;
        this.disconnectFromServer = other.disconnectFromServer;
        this.locked = other.locked;
        this.timeoutSeconds = other.timeoutSeconds;
    }

    public String getKeyword() { return keyword; }
    public void setKeyword(String v) { this.keyword = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isBlockMessage() { return blockMessage; }
    public void setBlockMessage(boolean v) { this.blockMessage = v; }
    public boolean isCensorWord() { return censorWord; }
    public void setCensorWord(boolean v) { this.censorWord = v; }
    public boolean isStripWord() { return stripWord; }
    public void setStripWord(boolean v) { this.stripWord = v; }
    public boolean isShowWarning() { return showWarning; }
    public void setShowWarning(boolean v) { this.showWarning = v; }
    public String getWarningText() { return warningText; }
    public void setWarningText(String v) { this.warningText = v == null ? "" : v; }
    public boolean isSendReplacement() { return sendReplacement; }
    public void setSendReplacement(boolean v) { this.sendReplacement = v; }
    public String getReplacementText() { return replacementText; }
    public void setReplacementText(String v) { this.replacementText = v == null ? "" : v; }
    public boolean isRequireConfirmation() { return requireConfirmation; }
    public void setRequireConfirmation(boolean v) { this.requireConfirmation = v; }
    public boolean isDisconnectFromServer() { return disconnectFromServer; }
    public void setDisconnectFromServer(boolean v) { this.disconnectFromServer = v; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean v) { this.locked = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { this.timeoutSeconds = Math.max(0, v); }

    /** Returns colored action icons for display in the rule list. */
    public String getActionIcons() {
        StringBuilder sb = new StringBuilder();
        if (locked) sb.append("\u00a74\u2666 ");
        if (blockMessage) sb.append("\u00a7c\u25a0 ");
        if (censorWord) sb.append("\u00a76*** ");
        if (stripWord) sb.append("\u00a7a\u2702 ");
        if (showWarning) sb.append("\u00a7e\u26a0 ");
        if (sendReplacement) sb.append("\u00a7b\u21bb ");
        if (requireConfirmation) sb.append("\u00a7d? ");
        if (disconnectFromServer) sb.append("\u00a74\u23cf ");
        if (timeoutSeconds > 0) sb.append("\u00a76" + timeoutSeconds + "s ");
        if (sb.isEmpty()) sb.append("\u00a78\u2014");
        return sb.toString().stripTrailing();
    }
}
