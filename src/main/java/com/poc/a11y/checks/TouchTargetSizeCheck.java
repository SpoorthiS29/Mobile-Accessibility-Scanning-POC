//package com.poc.a11y.checks;
//
//import com.poc.a11y.model.Issue;
//import com.poc.a11y.model.ScanRequest;
//import com.poc.a11y.model.Severity;
//import com.poc.a11y.model.UiElement;
//import org.springframework.stereotype.Component;
//
//import java.util.ArrayList;
//import java.util.List;
//
///**
// * Equivalent of ATF's TouchTargetSizeCheck: interactive elements should be
// * at least minTouchTargetDp x minTouchTargetDp so they're reliably tappable.
// * Threshold is converted from dp to px using the request's densityScale
// * (px = dp * density) since bounds in the page source are reported in px.
// */
//@Component
//public class TouchTargetSizeCheck implements AccessibilityCheck {
//
//    @Override
//    public String getId() {
//        return "SMALL_TOUCH_TARGET";
//    }
//
//    @Override
//    public List<Issue> evaluate(List<UiElement> elements, ScanRequest request) {
//        List<Issue> issues = new ArrayList<>();
//        double minPx = request.getMinTouchTargetDp() * request.getDensityScale();
//
//        for (UiElement el : elements) {
//            if (!el.isClickable() || !el.isEnabled()) continue;
//            if (el.widthPx() == 0 && el.heightPx() == 0) continue; // not laid out / off-screen
//
//            if (el.widthPx() < minPx || el.heightPx() < minPx) {
//                issues.add(new Issue(
//                        getId(),
//                        "Touch target smaller than " + request.getMinTouchTargetDp() + "dp",
//                        String.format(
//                                "Element <%s> measures %dx%d px (~%.1fx%.1f dp at density %.2f); " +
//                                        "recommended minimum is %ddp x %ddp.",
//                                el.getClassName(), el.widthPx(), el.heightPx(),
//                                el.widthPx() / request.getDensityScale(),
//                                el.heightPx() / request.getDensityScale(),
//                                request.getDensityScale(),
//                                request.getMinTouchTargetDp(), request.getMinTouchTargetDp()
//                        ),
//                        Severity.MODERATE,
//                        el,
//                        "2.5.5 Target Size"
//                ));
//            }
//        }
//        return issues;
//    }
//}
