package com.heg.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import com.heg.entity.User;
import java.util.List;

// Issue 1 Fixed: Removed 'public' from class
class UserControllerTest {

    private final UserController userController = new UserController();

    @Test
    // Issue 2 Fixed: Removed 'public' from method
    void testGetUsersCoverage() {
        List<User> result = userController.getUsers();
        
        assertNotNull(result);
        assertEquals(19, result.size()); 
    }
}