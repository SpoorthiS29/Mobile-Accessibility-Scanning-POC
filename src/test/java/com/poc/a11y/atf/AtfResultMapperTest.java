package com.poc.a11y.atf;

import com.poc.a11y.atf.dto.AtfCheckResultDto;
import com.poc.a11y.atf.dto.AtfScanOutputDto;
import com.poc.a11y.model.Issue;
import com.poc.a11y.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtfResultMapperTest {

    @Test
    void mapsLabelInNameMismatchToWcag253LevelA() {
        AtfCheckResultDto raw = new AtfCheckResultDto();
        raw.setCheckClass("LabelInNameMismatchCheck");
        raw.setType("ERROR");
        raw.setMessage("Visible label does not appear in the accessible name.");

        AtfScanOutputDto output = new AtfScanOutputDto();
        output.setResults(List.of(raw));

        List<Issue> issues = new AtfResultMapper().toIssues(output);

        assertEquals(1, issues.size());
        assertEquals("2.5.3 Label in Name", issues.get(0).getWcagCriterion());
        assertEquals("A", issues.get(0).getConformanceLevel());
        assertEquals(Severity.CRITICAL, issues.get(0).getSeverity());
        assertEquals("LabelInNameMismatchCheck", issues.get(0).getCheck());
    }

    @Test
    void mapsUnannouncedFormErrorToWcag331LevelA() {
        AtfCheckResultDto raw = new AtfCheckResultDto();
        raw.setCheckClass("UnannouncedFormErrorCheck");
        raw.setType("WARNING");
        raw.setMessage("Input field has nearby text that may be an error message.");

        AtfScanOutputDto output = new AtfScanOutputDto();
        output.setResults(List.of(raw));

        List<Issue> issues = new AtfResultMapper().toIssues(output);

        assertEquals(1, issues.size());
        assertEquals("3.3.1 Error Identification", issues.get(0).getWcagCriterion());
        assertEquals("A", issues.get(0).getConformanceLevel());
        assertEquals(Severity.MODERATE, issues.get(0).getSeverity());
        assertEquals("UnannouncedFormErrorCheck", issues.get(0).getCheck());
    }
}
