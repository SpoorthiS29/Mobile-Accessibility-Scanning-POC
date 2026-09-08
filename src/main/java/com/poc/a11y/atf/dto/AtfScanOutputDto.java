package com.poc.a11y.atf.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Top-level shape of the JSON file the atf-harness instrumentation test writes to device storage. */
@Data
public class AtfScanOutputDto {

    private double density;
    private int resultCount;
    private int viewportCount;
    private List<AtfCheckResultDto> results;

}
