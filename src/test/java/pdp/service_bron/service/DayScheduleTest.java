package pdp.service_bron.service;

import org.junit.jupiter.api.Test;
import pdp.service_bron.service.ScheduleService.DaySchedule;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validation of one weekday schedule. A working day never crosses midnight. */
class DayScheduleTest {

    private static LocalTime t(int h, int m) {
        return LocalTime.of(h, m);
    }

    @Test
    void validWorkingDayWithBreak() {
        assertThatCode(() -> DaySchedule.work(t(9, 0), t(20, 0), t(13, 0), t(14, 0)).validate()).doesNotThrowAnyException();
    }

    @Test
    void breakMayTouchTheEdgesOfTheWorkingDay() {
        assertThatCode(() -> DaySchedule.work(t(9, 0), t(20, 0), t(9, 0), t(10, 0)).validate()).doesNotThrowAnyException();
        assertThatCode(() -> DaySchedule.work(t(9, 0), t(20, 0), t(19, 0), t(20, 0)).validate()).doesNotThrowAnyException();
    }

    @Test
    void dayOffIsAlwaysValid() {
        assertThatCode(() -> DaySchedule.off().validate()).doesNotThrowAnyException();
    }

    @Test
    void endMustBeAfterStart() {
        assertThatThrownBy(() -> DaySchedule.work(t(20, 0), t(9, 0), null, null).validate())
                .isInstanceOf(BusinessException.class).hasMessage("hours.invalid_range");
        assertThatThrownBy(() -> DaySchedule.work(t(9, 0), t(9, 0), null, null).validate())
                .isInstanceOf(BusinessException.class).hasMessage("hours.invalid_range");
    }

    @Test
    void shiftAcrossMidnightIsRejected() {
        assertThatThrownBy(() -> DaySchedule.work(t(22, 0), t(2, 0), null, null).validate())
                .isInstanceOf(BusinessException.class).hasMessage("hours.invalid_range");
    }

    @Test
    void missingTimesAreRejected() {
        assertThatThrownBy(() -> DaySchedule.work(null, t(9, 0), null, null).validate())
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> DaySchedule.work(t(9, 0), null, null, null).validate())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void breakMustBeInsideWorkingHours() {
        assertThatThrownBy(() -> DaySchedule.work(t(9, 0), t(18, 0), t(8, 0), t(9, 30)).validate())
                .isInstanceOf(BusinessException.class).hasMessage("hours.invalid_break");
        assertThatThrownBy(() -> DaySchedule.work(t(9, 0), t(18, 0), t(17, 30), t(18, 30)).validate())
                .isInstanceOf(BusinessException.class).hasMessage("hours.invalid_break");
    }

    @Test
    void breakMustHavePositiveLengthAndBothEnds() {
        assertThatThrownBy(() -> DaySchedule.work(t(9, 0), t(18, 0), t(14, 0), t(13, 0)).validate())
                .isInstanceOf(BusinessException.class).hasMessage("hours.invalid_break");
        assertThatThrownBy(() -> DaySchedule.work(t(9, 0), t(18, 0), t(13, 0), null).validate())
                .isInstanceOf(BusinessException.class).hasMessage("hours.invalid_break");
    }
}
