package rexgen.videoproxy.protocol;

import java.util.Date;

public class RwcVaUtil {
    /**
     * Convert C-style time to Java Date
     */
    public static Date cTimeToDate(int time, int millisec) {
        // Convert C-style time_t to Java milliseconds
        long javaTime = ((long) time) * 1000 + millisec;
        return new Date(javaTime);
    }

    /**
     * Convert Java Date to C-style time
     */
    public static int dateToCTime(Date date) {
        return (int) (date.getTime() / 1000);
    }
}
