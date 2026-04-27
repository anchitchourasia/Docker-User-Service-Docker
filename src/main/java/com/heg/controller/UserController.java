package com.heg.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heg.entity.User;

@RestController
@RequestMapping("/users")
public class UserController {

    private List<User> users = new ArrayList<>(Arrays.asList(
            new User(1L, "Pradeep", "pradeep@example.com"),
            new User(2L, "Pramod", "pramod@example.com"),
            new User(3L, "Ravi", "ravi@example.com"),
            new User(4L, "Amit", "amit@example.com"),
            new User(5L, "Suresh", "suresh@example.com"),
            new User(6L, "Rahul", "rahul@example.com"),
            new User(7L, "Ankit", "ankit@example.com"),
            new User(8L, "Vikas", "vikas@example.com"),
            new User(9L, "Deepak", "deepak@example.com"),
            new User(10L, "Manoj", "manoj@example.com"),
            new User(11L, "Pooja", "pooja@example.com"),
            new User(12L, "Neha", "neha@example.com"),
            new User(13L, "Kiran", "kiran@example.com"),
            new User(104L, "Ritika", "ritika@example.com"),
            new User(105L, "Sneha", "sneha@example.com"),
            new User(106L, "Alok", "alok@example.com"),
            new User(107L, "Nitin", "nitin@example.com"),
            new User(108L, "Gaurav", "gaurav@example.com"),
            new User(109L, "Gaurav", "gaurav@example.com")
    ));

    // ✅ Get all users
    @GetMapping
    @Cacheable(value = "users")
    public List<User> getUsers() {
        return users;
    }

    // ✅ Get user by ID
    @GetMapping("/{id}")
    @Cacheable(value = "users", key = "#id")
    public User getUser(@PathVariable Long id) {
        return users.stream()
                .filter(u -> u.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    // ✅ Add new user
    @PostMapping
    @CacheEvict(value = "users", allEntries = true)
    public User addUser(@RequestBody User user) {
        user.setId((long) (users.size() + 1));
        users.add(user);
        return user;
    }

    // ✅ Update user
    @PutMapping
    @CacheEvict(value = "users", allEntries = true)
    public User updateUser(@RequestBody User user) {
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).getId().equals(user.getId())) {
                users.set(i, user);
                return user;
            }
        }
        return null;
    }

    // ✅ Delete user
    @DeleteMapping("/{id}")
    @CacheEvict(value = "users", allEntries = true)
    public String deleteUser(@PathVariable Long id) {
        users.removeIf(u -> u.getId().equals(id));
        return "User deleted successfully";
    }
}