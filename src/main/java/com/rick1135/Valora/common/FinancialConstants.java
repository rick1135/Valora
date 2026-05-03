package com.rick1135.Valora.common;

import java.math.RoundingMode;

public final class FinancialConstants {
    private FinancialConstants() {}

    public static final int MONETARY_SCALE = 2;
    public static final int PERCENTAGE_SCALE = 4;
    public static final int QUANTITY_SCALE = 8;
    public static final int EXTENDED_PRECISION_SCALE = 8;
    public static final int INTERMEDIATE_CALCULATION_SCALE = 10;
    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_UP;
}
