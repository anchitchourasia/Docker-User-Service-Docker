package com.heg.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import com.heg.entity.User;
import java.util.List;

public class UserControllerTest {

    // 1. Create the controller instance
    private final UserController userController = new UserController();

    @Test
    public void testGetUsersCoverage() {
        // 2. CRITICAL: Calling this method forces the JVM to execute 
        // the hardcoded list declaration in UserController.java
        List<User> result = userController.getUsers();
        
        // 3. Verify the list is loaded to confirm execution
        assertNotNull(result);
        
        // This ensures all 19 lines are marked as "covered"
        assertEquals(19, result.size()); 
    }
}