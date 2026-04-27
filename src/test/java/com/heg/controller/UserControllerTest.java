package com.heg.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import com.heg.entity.User;
import java.util.List;

public class UserControllerTest {

    // Instantiate the controller to trigger the initialization of your user list
    private final UserController userController = new UserController();

    @Test
    public void testGetUsers_ForSonarCoverage() {
        // This call executes the 'getUsers' method which returns the hardcoded list
        List<User> result = userController.getUsers();
        
        // Assertions confirm the code ran and the data exists
        assertNotNull(result, "The user list should be initialized");
        
        // Verify all 19 users are present to ensure the entire list was 'covered'
        assertTrue(result.size() >= 19, "Should contain at least the 19 hardcoded users");
        
        // Accessing the last user to ensure the end of the list was reached
        User lastUser = result.get(result.size() - 1);
        assertNotNull(lastUser.getName(), "User data should be accessible");
    }
}