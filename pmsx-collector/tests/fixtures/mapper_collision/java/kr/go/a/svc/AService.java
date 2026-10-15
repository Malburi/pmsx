package kr.go.a.svc;

import org.springframework.stereotype.Service;
import kr.go.a.emp.EmpMapper;

@Service
public class AService {
    private final EmpMapper empMapper;

    public AService(EmpMapper empMapper) { this.empMapper = empMapper; }

    public String run(String id) { return empMapper.selectName(id); }
}
