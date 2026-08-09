package com.solovis.entitlement.core.order;

import com.solovis.entitlement.core.model.EntitlementValue;
import java.util.Comparator;

/** {@link Generosity#compare} as a reusable {@link Comparator}. */
public final class ValueComparator implements Comparator<EntitlementValue> {

    public static final ValueComparator INSTANCE = new ValueComparator();

    private ValueComparator() {}

    @Override
    public int compare(EntitlementValue a, EntitlementValue b) {
        return Generosity.compare(a, b);
    }
}
