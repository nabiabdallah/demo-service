package com.example.demo.adapter.in.web;

import com.example.demo.domain.InvalidNameException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Spring binds a repeated {@code name} to one String joined with commas ({@code a,b}), which would greet a name
 * nobody sent. A name given more than once is rejected instead (400, values not repeated).
 */
final class NameParameter {

    private NameParameter() {}

    static String single(HttpServletRequest request, String bound) {
        var values = request.getParameterValues("name");
        if (values != null && values.length > 1) {
            throw new InvalidNameException();
        }
        return bound;
    }
}
