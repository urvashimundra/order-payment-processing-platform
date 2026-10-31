package com.paymentplatform.api_gateway_service.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Wraps a request so the gateway can (a) hide headers the client must not control and
 * (b) add headers it has verified itself (merchant id, key id, ...).
 */
public class HeaderAugmentingRequestWrapper extends HttpServletRequestWrapper {

    // HTTP header names are case-insensitive, so the lookup must be too
    private final Map<String, String> extraHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> blockedHeaders = new HashSet<>();   // stored lower-case

    public HeaderAugmentingRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    @Override
    public String getAuthType() {
        return super.getAuthType();
    }

    /** Hides a client-supplied header completely (used for identity headers). */
    public void blockHeader(String name) {
        blockedHeaders.add(name.toLowerCase(Locale.ROOT));
    }

    public void putHeader(String name, String value) {
        extraHeaders.put(name, value);
    }

    private boolean isBlocked(String name) {
        return name != null && blockedHeaders.contains(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public String getHeader(String name) {
        String value = extraHeaders.get(name);
        if (value != null) return value;
        return isBlocked(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String value = extraHeaders.get(name);
        if (value != null) return Collections.enumeration(Collections.singletonList(value));
        return isBlocked(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> names = new LinkedHashSet<>(extraHeaders.keySet());
        Enumeration<String> original = super.getHeaderNames();
        if (original != null) {
            for (String n : Collections.list(original)) {
                if (!isBlocked(n)) names.add(n);
            }
        }
        return Collections.enumeration(names);
    }
}
