package com.f;

/** 생성자 주입 + 필드 경유 흐름(STORE/LOAD) + DTO 세터. */
@Service("mainCustomerService")
public class CustomerServiceImpl implements CustomerService {
    private final CustomerMapper mapper;
    private String lastGrade;

    public CustomerServiceImpl(CustomerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int changeGrade(String id, String grade) {
        this.lastGrade = grade;
        Customer c = new Customer();
        c.setCustId(id);
        c.setCustGrade(grade);
        return mapper.updateGrade(c);
    }

    @Override
    public String lastGrade() {
        return lastGrade;
    }
}
