package com.aijiaoxue.identity;

import java.util.Set;

/** SQL inputs representing grants only; never user-supplied filters. */
public record OfferingScope(boolean global, Set<Long> teacherIds, Set<Long> offeringIds) {
    public OfferingScope {
        teacherIds = Set.copyOf(teacherIds);
        offeringIds = Set.copyOf(offeringIds);
    }
}
