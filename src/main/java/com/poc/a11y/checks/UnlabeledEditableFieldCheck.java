package com.poc.a11y.checks;

import com.poc.a11y.model.Issue;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.Severity;
import com.poc.a11y.model.UiElement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Equivalent of ATF's EditableContentDescCheck: EditText-style fields need
 * a content-description or hint so users know what to type, since the
 * (usually empty) "text" attribute of an empty field is not a label.
 */
@Component
public class UnlabeledEditableFieldCheck implements AccessibilityCheck {

    @Override
    public String getId() {
        return "UNLABELED_INPUT_FIELD";
    }

    @Override
    public List<Issue> evaluate(List<UiElement> elements, ScanRequest request) {
        List<Issue> issues = new ArrayList<>();
        for (UiElement el : elements) {
            boolean isEditable = el.getClassName() != null
                    && el.getClassName().toLowerCase().contains("edittext");

            if (isEditable && el.isEnabled()
                    && (el.getContentDesc() == null || el.getContentDesc().isBlank())) {
                issues.add(new Issue(
                        getId(),
                        "Input field has no accessible label",
                        "Editable field <" + el.getClassName() + "> has no content-description, " +
                                "so screen reader users won't know what to enter (placeholder text " +
                                "alone is not announced reliably).",
                        Severity.SERIOUS,
                        el,
                        "3.3.2 Labels or Instructions"
                ));
            }
        }
        return issues;
    }
}
