package com.hireai.offering;

import com.hireai.application.biz.offering.catalogue.impl.CatalogueReadAppServiceImpl;
import com.hireai.application.port.query.CatalogueQueryPort;
import com.hireai.utility.result.ResultCode;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogueReadAppServiceImplTest {

    @Mock
    CatalogueQueryPort catalogueQueryPort;

    private CatalogueReadAppServiceImpl service() {
        return new CatalogueReadAppServiceImpl(catalogueQueryPort);
    }

    @Test
    void unknownSortThrowsValidationError() {
        assertThatThrownBy(() -> service().search("", "", "bogus", 0, 10))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR))
                .hasMessageContaining("Unknown sort: bogus");
    }

    @Test
    void knownSortHotPassesThrough() {
        when(catalogueQueryPort.searchCards(any(), any(), eq("hot"), anyInt(), anyInt()))
                .thenReturn(List.of());

        service().search("", "", "hot", 0, 10);

        verify(catalogueQueryPort).searchCards("", "", "hot", 0, 10);
    }

    @Test
    void nullSortPassesThrough() {
        when(catalogueQueryPort.searchCards(any(), any(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service().search("", "", null, 0, 10);

        verify(catalogueQueryPort).searchCards("", "", null, 0, 10);
    }

    @Test
    void oversizedPageSizeClampedTo50() {
        when(catalogueQueryPort.searchCards(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service().search("", "", "hot", 0, 999);

        ArgumentCaptor<Integer> sizeCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(catalogueQueryPort).searchCards(any(), any(), any(), anyInt(), sizeCaptor.capture());
        assertThat(sizeCaptor.getValue()).isEqualTo(50);
    }
}
