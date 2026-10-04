package com.privacypilot.service;

import com.privacypilot.domain.User;
import com.privacypilot.repository.UserRepository;
import com.privacypilot.web.dto.CreateUserRequest;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public User create(CreateUserRequest req) {
        User user = new User(req.name(), req.email(), req.phone(), req.address());
        return users.save(user);
    }
}
