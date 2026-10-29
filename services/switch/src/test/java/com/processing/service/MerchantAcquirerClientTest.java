package com.processing.service;

import com.processing.config.SwitchFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit-тесты {@link MerchantAcquirerClient}: получение комиссии эквайринга
 * и деградация до {@code null} при сбоях.
 */
class MerchantAcquirerClientTest {

    private static final String FEE_URL = "http://localhost:8084/api/simulator/merchant/fee";

    @Test
    @DisplayName("Сервис вернул комиссию -> значение пробрасывается в Switch")
    void shouldReturnAcquiringFeeWhenServiceResponds() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MerchantAcquirerClient client = new MerchantAcquirerClient(SwitchFixtures.properties(), builder.build());
        server.expect(requestTo(FEE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"acquirerFee\": 25.50}", MediaType.APPLICATION_JSON));

        // Act
        BigDecimal fee = client.fetchAcquiringFee(
                SwitchFixtures.REQUEST_TIME, SwitchFixtures.STAN, SwitchFixtures.PAN,
                SwitchFixtures.TERMINAL_ID, SwitchFixtures.AMOUNT);

        // Assert
        assertThat(fee).isEqualByComparingTo("25.50");
        server.verify();
    }

    @Test
    @DisplayName("Пустое тело ответа -> null")
    void shouldReturnNullWhenResponseBodyIsEmpty() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MerchantAcquirerClient client = new MerchantAcquirerClient(SwitchFixtures.properties(), builder.build());
        server.expect(requestTo(FEE_URL)).andRespond(withSuccess());

        // Act
        BigDecimal fee = client.fetchAcquiringFee(
                SwitchFixtures.REQUEST_TIME, SwitchFixtures.STAN, SwitchFixtures.PAN,
                SwitchFixtures.TERMINAL_ID, SwitchFixtures.AMOUNT);

        // Assert
        assertThat(fee).isNull();
    }

    @Test
    @DisplayName("Сервис недоступен -> null, исключение не выходит наружу")
    void shouldReturnNullWhenServiceIsUnavailable() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MerchantAcquirerClient client = new MerchantAcquirerClient(SwitchFixtures.properties(), builder.build());
        server.expect(requestTo(FEE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Act
        BigDecimal fee = client.fetchAcquiringFee(
                SwitchFixtures.REQUEST_TIME, SwitchFixtures.STAN, SwitchFixtures.PAN,
                SwitchFixtures.TERMINAL_ID, SwitchFixtures.AMOUNT);

        // Assert
        assertThat(fee).isNull();
    }
}
