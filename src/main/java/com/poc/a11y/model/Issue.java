package com.poc.a11y.model;

import com.poc.a11y.atf.dto.AtfElementOutputDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Issue {

    private String checkId;
    private String title;
    private String description;
    private Severity severity;

//    private String elementXpath;
//    private String elementClass;
//    private String elementLabel;

    private AtfElementOutputDto element;

    private String wcagCriterion;
    private String screenshotPath;
    private String conformanceLevel;
    private String check;
    private String type;
    private String screenshot;

    public Issue(String checkId, String title, String description, Severity severity,
                 UiElement element, String wcagCriterion) {

        this.checkId = checkId;
        this.title = title;
        this.description = description;
        this.severity = severity;
        this.wcagCriterion = wcagCriterion;

        if (element != null) {
//            this.elementXpath = element.getXpath();
//            this.elementClass = element.getClassName();
//            this.elementLabel = element.effectiveLabel();

            AtfElementOutputDto outputElement = new AtfElementOutputDto();

            outputElement.setClassName(element.getClassName());
            outputElement.setResourceId(element.getResourceId());
            outputElement.setContentDescription(element.getContentDesc());
            outputElement.setText(element.getText());

//            outputElement.setClickable(element.isClickable());
//            outputElement.setFocusable(element.isFocusable());
//            outputElement.setCheckable(element.isCheckable());
//            outputElement.setEnabled(element.isEnabled());
//            outputElement.setPassword(element.isPassword());

            this.element = outputElement;
        }
    }
}
