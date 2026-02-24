package com.smeakmoseley.reinsmod.capability.controller;

public class AnimalController implements IAnimalController {

    private boolean controlling = false;

    @Override
    public boolean isControlling() {
        return controlling;
    }

    @Override
    public void setControlling(boolean value) {
        this.controlling = value;
    }
}
