package com.poc.a11y.atf.dto;

import lombok.Data;

@Data
public class AtfElementOutputDto {

    private String className;
    private String resourceId;
    private String contentDescription;
    private String text;

//    private boolean clickable;
//    private boolean focusable;
//    private boolean checkable;
//    private boolean enabled;
//    private boolean password;
}
