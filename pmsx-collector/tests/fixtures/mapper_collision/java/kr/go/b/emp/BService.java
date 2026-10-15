package kr.go.b.emp;

import org.springframework.stereotype.Service;

@Service
public class BService {
    @javax.annotation.Resource
    private EmpMapper empMapper;

    public String run(String id) { return empMapper.selectName(id); }
}
