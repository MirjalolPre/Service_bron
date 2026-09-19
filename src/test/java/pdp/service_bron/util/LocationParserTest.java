package pdp.service_bron.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pdp.service_bron.util.LocationParser.Coordinates;

import static org.assertj.core.api.Assertions.assertThat;

class LocationParserTest {

    private static Coordinates tashkent() {
        return new Coordinates(41.2856, 69.2036);
    }

    @ParameterizedTest
    @ValueSource(strings = {"41.2856, 69.2036", "41.2856,69.2036", "41.2856 69.2036", " 41.2856 ; 69.2036 "})
    void plainCoordinates(String text) {
        assertThat(LocationParser.parse(text)).contains(tashkent());
    }

    @Test
    void googleMapsLinks() {
        assertThat(LocationParser.parse("https://www.google.com/maps/@41.2856,69.2036,17z")).contains(tashkent());
        assertThat(LocationParser.parse("https://www.google.com/maps?q=41.2856,69.2036")).contains(tashkent());
        assertThat(LocationParser.parse("https://maps.google.com/?q=41.2856%2C69.2036")).contains(tashkent());
        assertThat(LocationParser.parse("https://www.google.com/maps/place/X/data=!3d41.2856!4d69.2036")).contains(tashkent());
    }

    @Test
    void yandexAndTwoGisWriteLongitudeFirst() {
        assertThat(LocationParser.parse("https://yandex.uz/maps/?ll=69.2036%2C41.2856&z=17")).contains(tashkent());
        assertThat(LocationParser.parse("https://yandex.com/maps/?pt=69.2036,41.2856&z=16")).contains(tashkent());
        assertThat(LocationParser.parse("https://2gis.uz/tashkent?m=69.2036%2C41.2856%2F17")).contains(tashkent());
    }

    @Test
    void negativeCoordinatesAreFine() {
        assertThat(LocationParser.parse("-33.8688, 151.2093")).contains(new Coordinates(-33.8688, 151.2093));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "hello", "91, 10", "10, 181", "https://maps.app.goo.gl/abcDEF123",
            "https://example.com/?q=Chilonzor", "41.2856"})
    void everythingElseIsRejected(String text) {
        assertThat(LocationParser.parse(text)).isEmpty();
    }

    @Test
    void nullIsRejected() {
        assertThat(LocationParser.parse(null)).isEmpty();
    }
}
