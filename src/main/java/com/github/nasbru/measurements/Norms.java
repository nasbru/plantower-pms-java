package com.github.nasbru.measurements;

public enum Norms
{
    PM2_5(25, 10),

    PM10(50, 20),
    
    CO2_Out(400, 400),
    
    CO2_In(1000, 1000);
    
    private Norms(int daily, int annual){
        this.daily = daily;
        this.annual = annual;
    }

    private int daily;
    private int annual;

    public int getDaily(){
        return daily;
    }

    public int getAnnual(){
        return annual;
    }
}
